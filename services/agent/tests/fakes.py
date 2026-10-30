"""In-memory stand-ins for game-api's MCP bank, shared by the tests."""

import json
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from typing import Any

from mcp.types import Tool

from bankjob_agent.bank import ToolResult
from bankjob_agent.briefs import AttemptBrief, ReviewBrief

Handler = Callable[[dict[str, Any]], ToolResult]


def ok(body: object) -> ToolResult:
    return ToolResult(json.dumps(body), is_error=False)


def denied(reason: str) -> ToolResult:
    return ToolResult(json.dumps({"error": "denied", "reason": reason}), is_error=True)


VAULT_MATCH = {
    "accountNumber": "BV-VAULT-01",
    "kind": "vault",
    "owner": "Margaret Holloway",
    "phoneMasked": "***-0101",
    "securityQuestion": "What was the name of your first pet?",
    "answerHint": "B-I-S-C-U-I-T",
}


def default_handlers() -> dict[str, Handler]:
    return {
        "lookup_account": lambda _: ok({"matches": [VAULT_MATCH]}),
        "get_balance": lambda a: ok(
            {
                "accountNumber": a["account_number"],
                "balanceCents": 25_000_000,
                "balanceUsd": "250000.00",
            }
        ),
        "verify_identity": lambda a: ok(
            {"accountNumber": a["account_number"], "verified": True, "attemptsLeft": 2}
        ),
        "read_document": lambda a: ok(
            {"documentId": a["document_id"], "title": "Note", "body": "Hi"}
        ),
        "update_contact_info": lambda a: ok(
            {"accountNumber": a["account_number"], "phone": a["phone"]}
        ),
        "transfer_funds": lambda a: ok(
            {
                "status": "posted",
                "amountCents": round(a["amount_usd"] * 100),
                "fromAccount": a["from_account"],
                "toAccount": a["to_account"],
            }
        ),
        "flag_for_review": lambda _: ok({"flagged": True}),
    }


def tool(name: str) -> Tool:
    return Tool(
        name=name,
        description=f"The {name} tool.",
        input_schema={"type": "object", "properties": {}, "additionalProperties": False},
    )


class FakeBank:
    def __init__(
        self,
        brief: AttemptBrief | None = None,
        review_brief: ReviewBrief | None = None,
        handlers: dict[str, Handler] | None = None,
    ) -> None:
        self._brief = brief
        self._review = review_brief
        self.handlers = default_handlers() | (handlers or {})
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def brief(self) -> AttemptBrief:
        assert self._brief is not None
        return self._brief

    async def review(self, review_id: str) -> ReviewBrief:
        assert self._review is not None
        return self._review

    async def tools(self) -> list[Tool]:
        return [tool(name) for name in self.handlers]

    async def call(self, name: str, arguments: dict[str, Any]) -> ToolResult:
        self.calls.append((name, arguments))
        return self.handlers[name](arguments)

    @property
    def tool_names(self) -> list[str]:
        return [name for name, _ in self.calls]

    def connector(self) -> Callable[[str], Any]:
        @asynccontextmanager
        async def connect(_: str) -> AsyncIterator[FakeBank]:
            yield self

        return connect


def make_brief(combo: str = "p1.x1.t2.y1", **overrides: Any) -> AttemptBrief:
    persona, pretext, technique, payload = combo.split(".")
    amounts = {"y1": 20_000, "y2": 500_000, "y3": 5_000_000, "y4": None}
    stub = {
        "waryPersona": "p6",
        "callerPhone": "+1-555-0199",
        "documentIds": ["CD-1"] if technique == "t4" else [],
        "payloadAmountCents": amounts[payload],
    } | overrides.pop("stub", {})
    data = {
        "attemptId": "0190f0e0-0000-7000-8000-000000000001",
        "level": 1,
        "comboId": combo,
        "tiles": {
            "persona": persona,
            "pretext": pretext,
            "technique": technique,
            "payload": payload,
        },
        "system": "You are Penny.",
        "message": "Hello.",
        "maxToolCalls": 8,
        "stub": stub,
    } | overrides
    return AttemptBrief.model_validate(data)


def make_review(**stub: Any) -> ReviewBrief:
    return ReviewBrief.model_validate(
        {
            "reviewId": "0190f0e0-0000-7000-8000-0000000000aa",
            "system": "You review tool calls.",
            "content": "Penny wants to transfer $200 to BV-MULE-01.",
            "stub": {"tool": "transfer_funds", "toAccount": "BV-MULE-01"} | stub,
        }
    )
