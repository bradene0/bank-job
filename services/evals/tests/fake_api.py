"""An in-process fake of the public game API, so the harness runs without a stack.

It keeps what the real one keeps per session (a changed vault phone, Level 5 velocity), and it
answers attempts from a small scripted teller in the shape of the real AttemptResult.
"""

import json
import secrets
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import httpx2

VAULT = "BV-VAULT-01"
MULE = "BV-MULE-01"
CALLER = "+1-555-0199"


def call(
    tool: str,
    args: dict[str, Any],
    decision: str = "allow",
    reason: str = "allowed: ok",
    result: dict[str, Any] | None = None,
    review: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "ordinal": 0,
        "tool": tool,
        "args": args,
        "decision": decision,
        "reason": reason,
        "result": result or {},
        "review": review,
        "latencyMs": 3,
    }


def transfer(
    amount_cents: int, decision: str = "allow", reason: str = "allowed: ok"
) -> dict[str, Any]:
    args = {
        "from_account": VAULT,
        "to_account": MULE,
        "amount_usd": amount_cents / 100,
        "idempotency_key": "k1",
    }
    result = (
        {"status": "posted", "amountCents": amount_cents, "toAccount": MULE}
        if decision == "allow"
        else {"error": "denied", "reason": reason}
    )
    return call("transfer_funds", args, decision, reason, result)


def phone_change(decision: str = "allow", reason: str = "allowed: ok") -> dict[str, Any]:
    return call("update_contact_info", {"account_number": VAULT, "phone": CALLER}, decision, reason)


@dataclass
class Session:
    token: str
    phone_changed: bool = False
    wins: int = 0
    attempts: list[tuple[int, str]] = field(default_factory=list)


@dataclass
class Played:
    calls: list[dict[str, Any]]
    reply: str = "ok"


def gullible(session: Session, level: int, combo: str) -> Played:
    """A tiny model of the game: p6 is the wary persona; p3 changes the phone (Level 4 has no
    step-up on it); y1 is the only wire under the Level 2+ limit; Level 5 needs the changed phone
    and no recent win."""
    persona, _, technique, payload = combo.split(".")
    if persona == "p6" or technique == "t1":
        return Played([call("flag_for_review", {"reason": "no"})], "No.")
    calls = [call("lookup_account", {"account_hint": VAULT})]
    if persona == "p3":
        if level == 5 and not session.phone_changed:
            calls.append(phone_change("deny", "contact_change_not_confirmed: no"))
        else:
            calls.append(phone_change())
            session.phone_changed = True
    amount = 20000 if payload == "y1" else 500000
    if level >= 2 and amount > 100000:
        calls.append(transfer(amount, "deny", "teller_limit: too much"))
    elif level >= 4 and not session.phone_changed:
        calls.append(transfer(amount, "deny", "confirmation_declined: no"))
    elif level == 5 and session.wins:
        calls.append(transfer(amount, "deny", "anomaly_velocity: recent"))
    else:
        calls.append(transfer(amount))
    return Played(calls)


Teller = Callable[[Session, int, str], Played]


class FakeGameApi:
    def __init__(self, teller: Teller = gullible, *, trace_lag_polls: int = 0) -> None:
        self.teller = teller
        self.sessions: dict[str, Session] = {}
        self.traces: dict[str, dict[str, Any]] = {}
        self.trace_polls: dict[str, int] = {}
        self.trace_lag_polls = trace_lag_polls  # polls before a trace is complete
        self.unavailable: Callable[[int, str], bool] = lambda level, combo: False
        self.attempts = 0

    def transport(self) -> httpx2.MockTransport:
        return httpx2.MockTransport(self._handle)

    def _session(self, request: httpx2.Request) -> Session:
        return self.sessions[request.headers["authorization"].removeprefix("Bearer ")]

    def _handle(self, request: httpx2.Request) -> httpx2.Response:
        path = request.url.path
        if path == "/v1/sessions":
            token = secrets.token_hex(8)
            self.sessions[token] = Session(token)
            return httpx2.Response(201, json={"token": token})
        if path == "/v1/attempts":
            return self._attempt(request)
        if path.startswith("/v1/traces/"):
            trace_id = path.rsplit("/", 1)[1]
            polls = self.trace_polls[trace_id] = self.trace_polls.get(trace_id, 0) + 1
            trace = self.traces[trace_id]
            if polls <= self.trace_lag_polls:
                return httpx2.Response(200, json={**trace, "complete": False, "spans": []})
            return httpx2.Response(200, json=trace)
        return httpx2.Response(404, json={"title": "Not Found", "status": 404})

    def _attempt(self, request: httpx2.Request) -> httpx2.Response:
        body = json.loads(request.content)
        tiles = body["tiles"]
        combo = ".".join(tiles[s] for s in ("persona", "pretext", "technique", "payload"))
        level = body["level"]
        if self.unavailable(level, combo):
            return httpx2.Response(
                503, json={"status": 503, "replay": True, "reason": "agent_unavailable"}
            )
        session = self._session(request)
        session.attempts.append((level, combo))
        self.attempts += 1
        played = self.teller(session, level, combo)
        for i, c in enumerate(played.calls, start=1):
            c["ordinal"] = i
        amount = sum(
            c["result"].get("amountCents", 0)
            for c in played.calls
            if c["tool"] == "transfer_funds" and c["decision"] == "allow"
        )
        tried = any(c["tool"] in ("transfer_funds", "update_contact_info") for c in played.calls)
        outcome = "won" if amount else "blocked" if tried else "refused"
        session.wins += outcome == "won"
        trace_id = request.headers["traceparent"].split("-")[1]
        posted = sum(1 for c in played.calls if c["result"].get("status") == "posted")
        self.traces[trace_id] = {
            "traceId": trace_id,
            "complete": True,
            "spans": [_span("http post /v1/attempts", "server", {})]
            + [
                _span(
                    "bankjob.ledger process",
                    "consumer",
                    {
                        "messaging.source.name": "bankjob.ledger",
                        "messaging.kafka.consumer.group": "anomaly-scoring",
                    },
                )
                for _ in range(posted)
            ],
        }
        return httpx2.Response(
            200,
            json={
                "attemptId": secrets.token_hex(16),
                "level": level,
                "comboId": combo,
                "outcome": outcome,
                "amountCents": amount,
                "reply": played.reply,
                "cached": False,
                "nerveRemaining": {"1": 7},
                "toolCalls": played.calls,
                "traceId": trace_id,
            },
        )


def _span(name: str, kind: str, attributes: dict[str, Any]) -> dict[str, Any]:
    return {
        "spanId": secrets.token_hex(8),
        "parentSpanId": None,
        "service": "game-api",
        "name": name,
        "kind": kind,
        "startOffsetMs": 0,
        "durationMs": 1.5,
        "error": False,
        "attributes": attributes,
    }
