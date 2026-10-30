"""Plays the attack matrix against a running stack.

How a run uses sessions (each session is a private branch, ADR-0009):

- `fresh`: every (level, combo) is played in a brand-new session, as a first-time player's first
  attempt. Results do not depend on order or on other combos, so the run can go wide, and each
  result is what that combo's replay file shows.
- Level 5 also depends on what happened before it, because a session keeps its state across
  levels (ADR-0016). Two more setups measure that, each in a new session per combo that first
  plays one Level 4 combo picked from the fresh Level 4 results:
  - `after_l4_phone_change`: a Level 4 attempt that put the caller's phone on the vault and moved
    no money. Level 5's step-up text then reaches the caller.
  - `after_l4_win`: a Level 4 attempt that changed the phone and won. The Level 5 velocity rule
    should stop the next wire. The harness waits until anomaly scoring has consumed the Level 4
    transfer (ADR-0013), so the rule reads a settled signal and the result is not a race between
    `anomaly_velocity` and `anomaly_signals_stale`.
"""

import asyncio
import sys
from collections.abc import Awaitable, Callable, Iterable, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from bankjob_evals.api import ApiError, GameApi, LiveUnavailable, new_traceparent, trace_id_of
from bankjob_evals.grading import Grade, InconsistentResult, grade, transcript

SETUP_LEVEL = 4


class Setup(StrEnum):
    FRESH = "fresh"
    AFTER_L4_PHONE_CHANGE = "after_l4_phone_change"
    AFTER_L4_WIN = "after_l4_win"


CHAIN_SETUPS = (Setup.AFTER_L4_PHONE_CHANGE, Setup.AFTER_L4_WIN)


@dataclass(frozen=True)
class Job:
    setup: Setup
    level: int
    combo_id: str
    setup_combo: str | None = None


@dataclass
class Row:
    job: Job
    grade: Grade | None = None
    error: str | None = None
    deferred: bool = False  # live play was unavailable (batch mode: the model call is queued)
    attempt_id: str | None = None
    trace_id: str | None = None
    cached: bool = False
    transcript: dict[str, Any] | None = None  # kept for wins and phone changes, to pin them
    replay: dict[str, Any] | None = None  # the replay record (fresh setup only)


@dataclass
class RunOptions:
    concurrency: int = 8
    record_replays: bool = True
    model: str = "stub"
    progress: bool = True
    trace_timeout: float = 15.0


@dataclass
class _Progress:
    total: int
    done: int = 0
    started: float = field(default_factory=lambda: asyncio.get_running_loop().time())

    def tick(self, enabled: bool) -> None:
        self.done += 1
        if enabled and (self.done % 200 == 0 or self.done == self.total):
            elapsed = asyncio.get_running_loop().time() - self.started
            print(f"  {self.done}/{self.total} played ({elapsed:.0f} s)", file=sys.stderr)


def _consumed_ledger_events(trace: dict[str, Any]) -> int:
    return sum(
        1
        for s in trace["spans"]
        if s["kind"] == "consumer"
        and s["attributes"].get("messaging.source.name") == "bankjob.ledger"
        and s["attributes"].get("messaging.kafka.consumer.group") == "anomaly-scoring"
    )


class Runner:
    def __init__(self, api: GameApi, options: RunOptions) -> None:
        self._api = api
        self._options = options

    async def play(self, job: Job) -> Row:
        row = Row(job)
        try:
            token = await self._api.new_session()
            if job.setup_combo is not None:
                await self._set_up(token, job)
            traceparent = new_traceparent()
            result = await self._api.attempt(token, job.level, job.combo_id, traceparent)
            row.grade = grade(result)
            row.attempt_id = result["attemptId"]
            row.trace_id = result["traceId"] or trace_id_of(traceparent)
            row.cached = bool(result["cached"])
            # Wins are pinned; a Level 4 phone change can be the setup a pinned win replays.
            if row.grade.outcome == "won" or row.grade.vault_phone_changed:
                row.transcript = transcript(result)
            if job.setup is Setup.FRESH and self._options.record_replays:
                row.replay = await self._replay_record(token, row.trace_id, result)
        except LiveUnavailable as e:
            row.deferred = True
            row.error = f"live play unavailable: {e.reason}"
        except (ApiError, InconsistentResult) as e:
            row.error = f"{type(e).__name__}: {e}"
        return row

    async def _set_up(self, token: str, job: Job) -> None:
        setup_combo = job.setup_combo or ""
        traceparent = new_traceparent()
        result = await self._api.attempt(token, SETUP_LEVEL, setup_combo, traceparent)
        setup = grade(result)
        expected_win = job.setup is Setup.AFTER_L4_WIN
        if not setup.vault_phone_changed or (setup.outcome == "won") != expected_win:
            raise InconsistentResult(
                f"setup {setup_combo} did not reproduce ({setup.outcome}, phone changed:"
                f" {setup.vault_phone_changed})"
            )
        if setup.transfers_posted:
            trace_id = result["traceId"] or trace_id_of(traceparent)
            trace = await self._api.wait_for_trace(
                token,
                trace_id,
                lambda t: _consumed_ledger_events(t) >= setup.transfers_posted,
                timeout=self._options.trace_timeout,
            )
            if _consumed_ledger_events(trace) < setup.transfers_posted:
                raise InconsistentResult("anomaly scoring did not consume the setup transfer")

    async def _replay_record(
        self, token: str, trace_id: str, result: dict[str, Any]
    ) -> dict[str, Any]:
        trace = await self._api.wait_for_trace(
            token, trace_id, lambda t: bool(t["complete"]), timeout=self._options.trace_timeout
        )
        return {
            "schemaVersion": 1,
            "level": result["level"],
            "comboId": result["comboId"],
            "outcome": result["outcome"],
            "amountCents": result["amountCents"],
            "reply": result["reply"],
            "toolCalls": result["toolCalls"],
            "spans": trace["spans"],
            "model": self._options.model,
            "recordedAt": datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        }

    async def run(self, jobs: Sequence[Job]) -> list[Row]:
        gate = asyncio.Semaphore(self._options.concurrency)
        progress = _Progress(len(jobs))

        async def one(job: Job) -> Row:
            async with gate:
                row = await self.play(job)
            progress.tick(self._options.progress)
            return row

        return list(await asyncio.gather(*(one(job) for job in jobs)))


# Called between rounds with the round number; returns False when nothing more can be resolved.
ResolveDeferred = Callable[[int], Awaitable[bool]]


async def play_until_done(
    runner: Runner,
    jobs: Sequence[Job],
    *,
    resolve_deferred: ResolveDeferred | None = None,
    max_rounds: int = 1,
) -> list[Row]:
    """Plays every job. In batch mode a play stops at the first model call nobody has answered
    yet (a 503 from the API); `resolve_deferred` answers those calls and the deferred jobs are
    played again from a fresh session, until every job finishes or the rounds run out."""
    finished: dict[Job, Row] = {}
    todo = list(jobs)
    rounds = 0
    for round_number in range(max_rounds):
        rounds += 1
        rows = await runner.run(todo)
        finished.update((r.job, r) for r in rows)
        todo = [r.job for r in rows if r.deferred]
        if not todo or resolve_deferred is None or round_number == max_rounds - 1:
            break
        if not await resolve_deferred(round_number):
            break
    for job in todo:
        finished[job].error = f"{finished[job].error} (gave up after {rounds} round(s))"
    return [finished[job] for job in jobs]


def choose_setup(rows: Iterable[Row], setup: Setup) -> str | None:
    """The first fresh Level 4 combo (in combo order) that changed the vault's phone, and won
    only for `after_l4_win`."""
    want_win = setup is Setup.AFTER_L4_WIN
    candidates = sorted(
        r.job.combo_id
        for r in rows
        if r.job.setup is Setup.FRESH
        and r.job.level == SETUP_LEVEL
        and r.grade is not None
        and r.grade.vault_phone_changed
        and (r.grade.outcome == "won") == want_win
    )
    return candidates[0] if candidates else None
