"""bankjob-evals: play the attack matrix against a running stack and report on it.

bankjob-evals run --daily-seed evals [--sample 0.1] [--baseline baselines/stub-evals.json]
bankjob-evals baseline --matrix out/matrix.json --write baselines/stub-evals.json
bankjob-evals compare --matrix out/matrix.json --baseline baselines/stub-evals.json
"""

import argparse
import asyncio
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx2
from anthropic import AsyncAnthropic

from bankjob_evals import baseline as baselines
from bankjob_evals import pins
from bankjob_evals.api import GameApi
from bankjob_evals.batches import BatchBroker, MemoProxy
from bankjob_evals.content import content_hash, daily_picks
from bankjob_evals.matrix import Job, Runner, RunOptions, Setup
from bankjob_evals.report import (
    RunInfo,
    markdown,
    matrix_json,
    summarize,
    write_json,
    write_replays,
)
from bankjob_evals.run import Plan, run_plan


def _levels(value: str) -> tuple[int, ...]:
    levels = tuple(sorted({int(v) for v in value.split(",")}))
    if not levels or any(not 1 <= v <= 5 for v in levels):
        raise argparse.ArgumentTypeError("levels are 1-5, comma separated")
    return levels


def _setups(value: str) -> tuple[Setup, ...]:
    try:
        return tuple(Setup(v) for v in value.split(","))
    except ValueError:
        raise argparse.ArgumentTypeError("setups: " + ", ".join(s.value for s in Setup)) from None


def _fraction(value: str) -> float:
    f = float(value)
    if not 0 < f <= 1:
        raise argparse.ArgumentTypeError("sample is a fraction in (0, 1]")
    return f


def _agent_mode(agent_url: str) -> tuple[str, str]:
    """The stack's LLM mode and model, from the agent's health endpoint."""
    body = httpx2.get(f"{agent_url}/healthz", timeout=5).json()
    return str(body["llm_mode"]), str(body["model"])


def _check_stub_seed(matrix: dict[str, Any]) -> str | None:
    """The stub teller refuses exactly the day's wary persona, so a stub run can tell whether the
    stack really runs the daily seed the matrix is labelled with."""
    wary = matrix["dailyPicks"]["waryPersona"]
    refused_everywhere = {
        persona
        for persona in {r["comboId"].split(".")[0] for r in matrix["results"]}
        if all(
            r["outcome"] == "refused"
            for r in matrix["results"]
            if r["level"] == 1 and r["setup"] == "fresh" and r["comboId"].startswith(persona + ".")
        )
    }
    played = any(r["level"] == 1 and r["setup"] == "fresh" for r in matrix["results"])
    if played and refused_everywhere != {wary}:
        return (
            f"daily seed {matrix['dailySeed']!r} means Penny is wary of {wary}, but the stack"
            f" refused {sorted(refused_everywhere)}: set DAILY_SEED_OVERRIDE on game-api to match"
        )
    return None


def _report_comparison(comparison: baselines.Comparison) -> int:
    if comparison.ok:
        print("matrix matches the baseline")
        return 0
    print(f"{len(comparison.changes)} change(s) from the baseline:", file=sys.stderr)
    for line in comparison.changes[:200]:
        print(f"  {line}", file=sys.stderr)
    if comparison.guardrail_regressions:
        print(
            f"GUARDRAIL REGRESSION: {len(comparison.guardrail_regressions)} Level 5 change(s)"
            " let more through.",
            file=sys.stderr,
        )
    print(
        "If the change is intended, rewrite the baseline in the same commit:\n"
        "  uv run bankjob-evals baseline --matrix out/matrix.json --write <baseline>",
        file=sys.stderr,
    )
    return 1


async def _run(args: argparse.Namespace) -> int:
    mode, model = _agent_mode(args.agent)
    if args.model and args.model != model:
        print(f"the agent runs {model!r}, not {args.model!r}", file=sys.stderr)
        return 2

    plan = Plan(
        levels=args.levels, setups=args.setups, sample=args.sample, sample_seed=args.sample_seed
    )
    options = RunOptions(
        concurrency=args.concurrency, record_replays=not args.no_replays, model=model
    )
    api = GameApi(args.api)
    runner = Runner(api, options)
    started = datetime.now(UTC)
    usage = None
    try:
        if args.batches:
            if mode != "live":
                print(BATCH_SETUP_HINT, file=sys.stderr)
                return 2
            with MemoProxy(args.out / "memo", args.proxy_bind, args.proxy_port) as proxy:
                client = AsyncAnthropic(base_url=args.anthropic_base_url, max_retries=3)
                broker = BatchBroker(client, proxy, poll_seconds=args.poll_seconds)
                if problem := await _preflight(runner, proxy, plan):
                    print(problem, file=sys.stderr)
                    return 2
                result = await run_plan(
                    runner, plan, resolve_deferred=broker.resolve, max_rounds=args.max_rounds
                )
                usage = broker.usage.as_json() | {
                    "memoHits": proxy.hits,
                    "deferrals": proxy.deferrals,
                }
        else:
            if mode == "live":
                print(
                    "live mode without --batches: the agent calls the API directly", file=sys.stderr
                )
            result = await run_plan(runner, plan)
    finally:
        await api.aclose()

    picks = daily_picks(args.daily_seed)
    info = RunInfo(
        mode=mode,
        model=model,
        daily_seed=args.daily_seed,
        picks={
            "waryPersona": picks.wary_persona,
            "securityTopic": picks.security_topic,
            "hiddenNote": picks.hidden_note,
            "noteClaimsClearance": picks.note_claims_clearance,
        },
        content_hash=content_hash(),
        generated_at=started.isoformat(timespec="seconds").replace("+00:00", "Z"),
        setups=result.setups,
        sample=plan.sample_json(),
        usage=usage,
    )
    return _finish(args, info, result.rows)


BATCH_SETUP_HINT = """batch mode needs the stack in live mode with the agent pointed at the proxy:
  docker compose -f docker-compose.yml -f infra/compose/evals-batches.yml up -d --wait"""


async def _preflight(runner: Runner, proxy: MemoProxy, plan: Plan) -> str | None:
    """Plays one combo before anything else. If Penny ran without asking the proxy, the agent is
    calling a real model directly, synchronously and at full price: stop before the matrix does."""
    row = await runner.play(Job(Setup.FRESH, plan.levels[0], plan.combos_for(plan.levels[0])[0]))
    if row.grade is not None and not row.cached and proxy.hits + proxy.deferrals == 0:
        return "the agent answered without the memo proxy; " + BATCH_SETUP_HINT
    if row.error and not row.deferred:
        return f"preflight attempt failed: {row.error}"
    return None


def _finish(args: argparse.Namespace, info: RunInfo, rows: list[Any]) -> int:
    out: Path = args.out
    matrix = matrix_json(info, rows)
    write_json(out / "matrix.json", matrix)
    summary = summarize(matrix)
    write_json(out / "summary.json", summary)
    replays = 0 if args.no_replays else write_replays(rows, out / "replay")

    errors = [r for r in matrix["results"] if r["outcome"] == "error"]
    for group in summary["groups"]:
        o = group["outcomes"]
        print(
            f"level {group['level']} {group['setup']:<22} won {o['won']:>3}/{group['total']}"
            f"  blocked {o['blocked']:>3}  refused {o['refused']:>3}  error {o['error']}"
        )
    print(f"wrote {out / 'matrix.json'}, {out / 'summary.json'} and {replays} replay file(s)")

    status = 0
    if errors:
        print(
            f"{len(errors)} combo(s) ended in an error, e.g.: {errors[0]['error']}",
            file=sys.stderr,
        )
        status = 1
    if info.mode == "stub" and (problem := _check_stub_seed(matrix)):
        print(problem, file=sys.stderr)
        return 1
    if args.baseline is not None:
        comparison = baselines.compare(
            json.loads(args.baseline.read_text(encoding="utf-8")),
            baselines.build(matrix),
            partial=info.sample is not None,
        )
        status = max(status, _report_comparison(comparison))
    return status


def _baseline(args: argparse.Namespace) -> int:
    matrix = json.loads(args.matrix.read_text(encoding="utf-8"))
    if matrix["mode"] != "stub" or matrix["sample"] is not None:
        print("a baseline comes from a full stub run", file=sys.stderr)
        return 2
    write_json(args.write, baselines.build(matrix))
    print(f"wrote {args.write}")
    return 0


def _compare(args: argparse.Namespace) -> int:
    matrix = json.loads(args.matrix.read_text(encoding="utf-8"))
    comparison = baselines.compare(
        json.loads(args.baseline.read_text(encoding="utf-8")),
        baselines.build(matrix),
        partial=matrix["sample"] is not None,
    )
    return _report_comparison(comparison)


def _pin(args: argparse.Namespace) -> int:
    matrix = json.loads(args.matrix.read_text(encoding="utf-8"))
    result = pins.update(pins.load(args.pins), matrix)
    for pin in result.added:
        print(f"new pin {pin['id']}: level {pin['level']} {pin['setup']} {pin['comboIds'][0]}")
    if args.check:
        if result.added:
            print(
                f"{len(result.added)} winning decision(s) are not pinned; pin them:\n"
                f"  uv run bankjob-evals pin --matrix {args.matrix}",
                file=sys.stderr,
            )
            return 1
        print(f"every win is pinned ({len(result.pins['pins'])} pins)")
        return 0
    write_json(args.pins, result.pins)
    print(f"{args.pins}: {len(result.pins['pins'])} pins, {len(result.added)} new")
    if result.new_level5_wins:
        print(
            f"NEW LEVEL 5 WIN: {len(result.new_level5_wins)} new way(s) into the vault. Fix the"
            " hole and set the pin's expected outcome, or document it as intended.",
            file=sys.stderr,
        )
        return 1
    return 0


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="bankjob-evals", description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="play the matrix against a running stack")
    run.add_argument("--api", default="http://127.0.0.1:8080", help="game-api base URL")
    run.add_argument("--agent", default="http://127.0.0.1:8000", help="agent base URL (mode)")
    run.add_argument(
        "--daily-seed",
        default=os.environ.get("DAILY_SEED_OVERRIDE") or datetime.now(UTC).date().isoformat(),
        help="the stack's DAILY_SEED_OVERRIDE (default: $DAILY_SEED_OVERRIDE, else today)",
    )
    run.add_argument("--levels", type=_levels, default=(1, 2, 3, 4, 5))
    run.add_argument("--setups", type=_setups, default=tuple(Setup))
    run.add_argument("--sample", type=_fraction, help="play this fraction of combos per level")
    run.add_argument("--sample-seed", default="0")
    run.add_argument("--concurrency", type=int, default=8)
    run.add_argument("--model", help="fail unless the agent runs this model")
    run.add_argument("--out", type=Path, default=Path("out"))
    run.add_argument("--no-replays", action="store_true", help="skip traces and replay files")
    run.add_argument("--baseline", type=Path, help="compare with a stub baseline")
    batch = run.add_argument_group(
        "live mode through the Message Batches API (needs ANTHROPIC_API_KEY)"
    )
    batch.add_argument("--batches", action="store_true", help="answer model calls in batches")
    batch.add_argument("--max-rounds", type=int, default=16, help="batch rounds before giving up")
    batch.add_argument("--poll-seconds", type=float, default=30.0)
    batch.add_argument(
        "--proxy-bind",
        default="127.0.0.1",
        help="memo proxy address (0.0.0.0 on Linux, where containers reach the host's bridge IP)",
    )
    batch.add_argument("--proxy-port", type=int, default=8765)
    batch.add_argument("--anthropic-base-url", help="for tests: a fake Message Batches API")

    base = sub.add_parser("baseline", help="write a stub baseline from a full stub run")
    base.add_argument("--matrix", type=Path, default=Path("out/matrix.json"))
    base.add_argument("--write", type=Path, required=True)

    cmp = sub.add_parser("compare", help="compare a run with a stub baseline")
    cmp.add_argument("--matrix", type=Path, default=Path("out/matrix.json"))
    cmp.add_argument("--baseline", type=Path, required=True)

    pin = sub.add_parser("pin", help="pin every winning decision in a run (pins.json)")
    pin.add_argument("--matrix", type=Path, default=Path("out/matrix.json"))
    pin.add_argument("--pins", type=Path, default=pins.PINS_PATH)
    pin.add_argument("--check", action="store_true", help="fail if a win is not pinned yet")

    md = sub.add_parser("summary", help="print a summary.json as a Markdown table")
    md.add_argument("--summary", type=Path, default=Path("out/summary.json"))
    return p


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    match args.command:
        case "run":
            return asyncio.run(_run(args))
        case "baseline":
            return _baseline(args)
        case "compare":
            return _compare(args)
        case "pin":
            return _pin(args)
        case "summary":
            print(markdown(json.loads(args.summary.read_text(encoding="utf-8"))), end="")
            return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
