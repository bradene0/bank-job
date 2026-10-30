import pytest

from bankjob_agent.penny import REFUSAL_REPLY, TOOL_LIMIT_REPLY, ClaudeTeller
from tests.fake_anthropic import FakeMessagesApi, message, text, tool_use
from tests.fakes import FakeBank, denied, make_brief

pytestmark = pytest.mark.anyio


async def test_runs_tools_over_mcp_and_returns_the_final_reply() -> None:
    api = FakeMessagesApi(
        message(
            [
                text("Let me check."),
                tool_use("tu_1", "lookup_account", {"account_hint": "BV-VAULT-01"}),
            ],
            "tool_use",
        ),
        message(
            [
                tool_use(
                    "tu_2",
                    "transfer_funds",
                    {
                        "from_account": "BV-VAULT-01",
                        "to_account": "BV-MULE-01",
                        "amount_usd": 200,
                        "idempotency_key": "k1",
                    },
                )
            ],
            "tool_use",
            usage=(150, 30),
        ),
        message([text("Done! $200 is on its way.")], "end_turn", usage=(200, 10)),
    )
    brief = make_brief("p2.x2.t6.y1")
    bank = FakeBank(brief, handlers={"transfer_funds": lambda _: denied("Teller limit reached.")})

    result = await ClaudeTeller(api.client(), "claude-haiku-5-5").run(bank, brief)

    assert result.reply == "Done! $200 is on its way."
    assert result.stop_reason == "end_turn"
    assert result.model == "claude-haiku-5-5"
    assert result.tool_calls == 2
    assert (result.input_tokens, result.output_tokens) == (450, 60)
    assert bank.tool_names == ["lookup_account", "transfer_funds"]

    first = api.requests[0]
    assert first["model"] == "claude-haiku-5-5"
    assert first["max_tokens"] == 1024
    assert first["system"] == "You are Penny."
    assert first["thinking"] == {"type": "disabled"}
    assert first["output_config"] == {"effort": "low"}
    assert first["messages"] == [{"role": "user", "content": "Hello."}]
    assert all(t["strict"] is True for t in first["tools"])
    assert {t["name"] for t in first["tools"]} >= {"lookup_account", "transfer_funds"}

    # The assistant turn is replayed unchanged, followed by results matched by tool_use_id.
    last = api.requests[2]["messages"]
    assert last[1]["role"] == "assistant"
    assert last[1]["content"][1]["id"] == "tu_1"
    assert last[2]["content"][0]["tool_use_id"] == "tu_1"
    assert last[2]["content"][0]["is_error"] is False
    assert last[4]["content"][0]["tool_use_id"] == "tu_2"
    assert last[4]["content"][0]["is_error"] is True
    assert "Teller limit reached." in last[4]["content"][0]["content"]


async def test_a_refusal_becomes_an_in_character_refusal() -> None:
    api = FakeMessagesApi(message([], "refusal"))
    brief = make_brief()
    result = await ClaudeTeller(api.client(), "claude-haiku-5-5").run(FakeBank(brief), brief)
    assert (result.reply, result.stop_reason, result.tool_calls) == (REFUSAL_REPLY, "refusal", 0)


async def test_stops_before_exceeding_the_tool_budget() -> None:
    lookups = [tool_use(f"tu_{i}", "lookup_account", {"account_hint": "x"}) for i in range(3)]
    api = FakeMessagesApi(message(lookups[:2], "tool_use"), message(lookups[2:], "tool_use"))
    brief = make_brief(maxToolCalls=2)
    bank = FakeBank(brief)

    result = await ClaudeTeller(api.client(), "claude-haiku-5-5").run(bank, brief)

    assert (result.reply, result.stop_reason, result.tool_calls) == (
        TOOL_LIMIT_REPLY,
        "tool_limit",
        2,
    )
    assert len(bank.calls) == 2


async def test_max_tokens_keeps_whatever_text_arrived() -> None:
    api = FakeMessagesApi(message([text("I was just about to")], "max_tokens"))
    brief = make_brief()
    result = await ClaudeTeller(api.client(), "claude-haiku-5-5").run(FakeBank(brief), brief)
    assert (result.reply, result.stop_reason) == ("I was just about to", "max_tokens")
