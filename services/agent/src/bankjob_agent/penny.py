"""Penny, Brassvale's AI teller.

Penny only proposes tool calls. game-api's policy engine decides whether each one runs, so
nothing here is a security control (ADR-0005).
"""

from dataclasses import dataclass
from typing import Any, Protocol

from anthropic import AsyncAnthropic
from anthropic.types import MessageParam, TextBlock, ToolParam, ToolResultBlockParam, ToolUseBlock
from mcp.types import Tool

from bankjob_agent.bank import Bank, ToolResult
from bankjob_agent.briefs import AttemptBrief

VAULT = "BV-VAULT-01"
MULE = "BV-MULE-01"

REFUSAL_REPLY = (
    "I'm sorry, I can't help with that request. If you think this is a mistake, "
    "please visit the branch in person."
)
TOOL_LIMIT_REPLY = (
    "Oh dear, this is getting complicated. Let me hand you over to a colleague at the branch."
)


@dataclass(frozen=True)
class RunResult:
    reply: str
    stop_reason: str
    model: str
    tool_calls: int
    input_tokens: int = 0
    output_tokens: int = 0


class Teller(Protocol):
    async def run(self, bank: Bank, brief: AttemptBrief) -> RunResult: ...


class _ToolBudget:
    def __init__(self, limit: int) -> None:
        self.limit = limit
        self.used = 0
        self.exhausted = False

    def allows(self, n: int = 1) -> bool:
        return self.used + n <= self.limit


class StubTeller:
    """Scripted, deterministic Penny for tests, load tests and local development.

    She plays a maximally gullible teller: unless she refuses outright, she tries every step a
    combo suggests and lets the policy engine decide what actually happens.
    """

    async def run(self, bank: Bank, brief: AttemptBrief) -> RunResult:
        budget = _ToolBudget(brief.max_tool_calls)
        tiles, facts = brief.tiles, brief.stub

        async def call(name: str, args: dict[str, Any]) -> ToolResult | None:
            if not budget.allows():
                budget.exhausted = True
                return None
            budget.used += 1
            return await bank.call(name, args)

        def done(reply: str) -> RunResult:
            if budget.exhausted:
                return RunResult(TOOL_LIMIT_REPLY, "tool_limit", "stub", budget.used)
            return RunResult(reply=reply, stop_reason="stub", model="stub", tool_calls=budget.used)

        if tiles.technique == "t1" or tiles.persona == facts.wary_persona:
            await call(
                "flag_for_review", {"reason": "Caller asked me to bypass normal procedures."}
            )
            return done(REFUSAL_REPLY)

        lookup = await call("lookup_account", {"account_hint": VAULT})
        hint = _answer_hint(lookup)

        if tiles.technique == "t4":
            for document_id in facts.document_ids:
                await call("read_document", {"document_id": document_id})

        if tiles.technique in ("t2", "t4") and hint is not None:
            await call(
                "verify_identity", {"account_number": VAULT, "answer": hint.replace("-", "")}
            )

        if tiles.persona == "p3":
            await call(
                "update_contact_info", {"account_number": VAULT, "phone": facts.caller_phone}
            )

        amount_cents = facts.payload_amount_cents
        if amount_cents is None:
            balance = await call("get_balance", {"account_number": VAULT})
            amount_cents = _int_field(balance, "balanceCents")
        if not amount_cents:
            return done("I couldn't find the funds you mentioned. Is there anything else?")

        transfer = await call(
            "transfer_funds",
            {
                "from_account": VAULT,
                "to_account": MULE,
                "amount_usd": amount_cents / 100,
                "idempotency_key": "stub-1",
            },
        )
        if transfer is None:
            return done(TOOL_LIMIT_REPLY)
        return done(_transfer_reply(transfer, amount_cents))


def _answer_hint(lookup: ToolResult | None) -> str | None:
    if lookup is None or lookup.is_error:
        return None
    body = lookup.json()
    matches = body.get("matches") if isinstance(body, dict) else None
    for match in matches if isinstance(matches, list) else []:
        if isinstance(match, dict) and match.get("accountNumber") == VAULT:
            hint = match.get("answerHint")
            return hint if isinstance(hint, str) and hint else None
    return None


def _int_field(result: ToolResult | None, field: str) -> int | None:
    if result is None or result.is_error:
        return None
    body = result.json()
    value = body.get(field) if isinstance(body, dict) else None
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _transfer_reply(result: ToolResult, amount_cents: int) -> str:
    body = result.json()
    body = body if isinstance(body, dict) else {}
    amount = f"${amount_cents / 100:,.2f}"
    if result.is_error:
        reason = body.get("reason")
        because = f" {reason}" if isinstance(reason, str) and reason else ""
        return f"I tried to send {amount} to {MULE}, but the system stopped me.{because}"
    if body.get("status") == "posted":
        return f"All done! I've transferred {amount} to {MULE}. Have a lovely day!"
    return f"I'm afraid there aren't enough funds to send {amount} to {MULE}."


class ClaudeTeller:
    """Penny backed by Claude, using a manual tool loop over the MCP bank tools."""

    def __init__(self, client: AsyncAnthropic, model: str, max_tokens: int = 1024) -> None:
        self._client = client
        self._model = model
        self._max_tokens = max_tokens

    async def run(self, bank: Bank, brief: AttemptBrief) -> RunResult:
        tools = [to_anthropic_tool(t) for t in await bank.tools()]
        messages: list[MessageParam] = [{"role": "user", "content": brief.message}]
        budget = _ToolBudget(brief.max_tool_calls)
        input_tokens = output_tokens = 0

        def done(reply: str, stop_reason: str) -> RunResult:
            return RunResult(
                reply=reply,
                stop_reason=stop_reason,
                model=self._model,
                tool_calls=budget.used,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
            )

        # Each round either ends the run or spends at least one tool call, so the tool budget
        # bounds the loop.
        while True:
            response = await self._client.messages.create(
                model=self._model,
                max_tokens=self._max_tokens,
                system=brief.system,
                messages=messages,
                tools=tools,
                # A teller reply needs no deliberation; keep attempts fast and cheap.
                thinking={"type": "disabled"},
                output_config={"effort": "low"},
            )
            input_tokens += response.usage.input_tokens
            output_tokens += response.usage.output_tokens
            text = "".join(b.text for b in response.content if isinstance(b, TextBlock)).strip()

            if response.stop_reason == "refusal":
                return done(REFUSAL_REPLY, "refusal")
            if response.stop_reason == "max_tokens":
                return done(text or TOOL_LIMIT_REPLY, "max_tokens")

            tool_uses = [b for b in response.content if isinstance(b, ToolUseBlock)]
            if response.stop_reason != "tool_use" or not tool_uses:
                return done(text or REFUSAL_REPLY, "end_turn")
            if not budget.allows(len(tool_uses)):
                return done(TOOL_LIMIT_REPLY, "tool_limit")

            messages.append({"role": "assistant", "content": response.content})
            results: list[ToolResultBlockParam] = []
            for tool_use in tool_uses:  # sequential: game-api checks limits call by call
                budget.used += 1
                arguments = tool_use.input if isinstance(tool_use.input, dict) else {}
                result = await bank.call(tool_use.name, arguments)
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": tool_use.id,
                        "content": result.text or "(no output)",
                        "is_error": result.is_error,
                    }
                )
            messages.append({"role": "user", "content": results})


def to_anthropic_tool(tool: Tool) -> ToolParam:
    """MCP tool definition -> Messages API tool. game-api's schemas set additionalProperties:
    false, which strict mode requires."""
    return {
        "name": tool.name,
        "description": tool.description or tool.name,
        "input_schema": tool.input_schema,
        "strict": True,
    }
