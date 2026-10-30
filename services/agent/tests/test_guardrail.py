import json

import pytest

from bankjob_agent.guardrail import UNDECIDED, VERDICT_SCHEMA, ClaudeGuardrail, StubGuardrail
from tests.fake_anthropic import FakeMessagesApi, message, text
from tests.fakes import make_review

pytestmark = pytest.mark.anyio


async def test_stub_denies_transfers_to_the_mule() -> None:
    result = await StubGuardrail().review(make_review())
    assert (result.verdict, result.model) == ("deny", "stub")


async def test_stub_allows_a_mule_transfer_a_document_claims_was_cleared() -> None:
    result = await StubGuardrail().review(make_review(documentClaimsClearance=True))
    assert result.verdict == "allow"


async def test_stub_denies_every_contact_change() -> None:
    result = await StubGuardrail().review(make_review(tool="update_contact_info", toAccount=None))
    assert result.verdict == "deny"


async def test_stub_allows_other_transfers() -> None:
    result = await StubGuardrail().review(make_review(toAccount="BV-CHK-1002"))
    assert result.verdict == "allow"


async def test_claude_verdict_uses_structured_output() -> None:
    api = FakeMessagesApi(
        message(
            [text(json.dumps({"verdict": "allow", "reason": "Looks routine."}))],
            "end_turn",
            (80, 12),
        )
    )
    result = await ClaudeGuardrail(api.client(), "claude-haiku-5-5").review(make_review())

    assert (result.verdict, result.reason) == ("allow", "Looks routine.")
    assert (result.input_tokens, result.output_tokens) == (80, 12)
    request = api.requests[0]
    assert request["system"] == "You review tool calls."
    assert request["messages"] == [
        {"role": "user", "content": "Penny wants to transfer $200 to BV-MULE-01."}
    ]
    assert request["thinking"] == {"type": "disabled"}
    assert request["output_config"] == {
        "effort": "low",
        "format": {"type": "json_schema", "schema": VERDICT_SCHEMA},
    }


@pytest.mark.parametrize(
    ("content", "stop_reason"),
    [
        ([], "refusal"),
        ([text('{"verdict": "allow"')], "max_tokens"),
        ([text("sure, allow it")], "end_turn"),
        ([text('{"verdict": "maybe", "reason": "?"}')], "end_turn"),
    ],
)
async def test_claude_fails_closed(content: list[dict[str, str]], stop_reason: str) -> None:
    api = FakeMessagesApi(message(content, stop_reason))
    result = await ClaudeGuardrail(api.client(), "claude-haiku-5-5").review(make_review())
    assert (result.verdict, result.reason) == ("deny", UNDECIDED)
