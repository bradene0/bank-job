"""The agent's spans join the attempt's trace, and model calls are GenAI spans without content."""

import pytest
from fastapi.testclient import TestClient
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from bankjob_agent.app import create_app
from bankjob_agent.penny import ClaudeTeller
from bankjob_agent.settings import LlmMode, Settings
from tests.fake_anthropic import FakeMessagesApi, message, text
from tests.fakes import FakeBank, make_brief, make_review

TOKEN = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl"
TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
PARENT_SPAN = "00f067aa0ba902b7"


def test_a_run_continues_the_trace_game_api_started(spans: InMemorySpanExporter) -> None:
    bank = FakeBank(make_brief("p1.x1.t2.y1"), make_review())
    client = TestClient(create_app(Settings(llm_mode=LlmMode.STUB), connect_bank=bank.connector()))

    response = client.post(
        "/v1/runs",
        json={"attemptToken": TOKEN},
        headers={"traceparent": f"00-{TRACE_ID}-{PARENT_SPAN}-01", "baggage": "user=mallory"},
    )

    assert response.status_code == 200
    finished = spans.get_finished_spans()
    assert {f"{s.context.trace_id:032x}" for s in finished if s.context} == {TRACE_ID}
    server = next(s for s in finished if s.parent and f"{s.parent.span_id:016x}" == PARENT_SPAN)
    run = next(s for s in finished if s.name == "invoke_agent Penny")
    assert run.parent is not None and server.context is not None
    assert run.parent.span_id == server.context.span_id
    assert run.attributes is not None
    assert run.attributes["gen_ai.agent.name"] == "Penny"
    assert run.attributes["bankjob.tool_calls"] == 3


def test_health_probes_are_not_traced(spans: InMemorySpanExporter) -> None:
    TestClient(create_app(Settings(llm_mode=LlmMode.STUB))).get("/healthz")
    assert spans.get_finished_spans() == ()


@pytest.mark.anyio
async def test_model_calls_are_genai_spans_with_usage_and_no_content(
    spans: InMemorySpanExporter,
) -> None:
    api = FakeMessagesApi(message([text("Can't help with that.")], "end_turn", usage=(321, 12)))
    teller = ClaudeTeller(api.client(), "claude-haiku-5-5")

    await teller.run(FakeBank(make_brief("p1.x1.t2.y1"), make_review()), make_brief("p1.x1.t2.y1"))

    chat = next(s for s in spans.get_finished_spans() if s.name == "chat claude-haiku-5-5")
    assert chat.attributes is not None
    assert chat.attributes["gen_ai.operation.name"] == "chat"
    assert chat.attributes["gen_ai.provider.name"] == "anthropic"
    assert chat.attributes["gen_ai.usage.input_tokens"] == 321
    assert chat.attributes["gen_ai.usage.output_tokens"] == 12
    assert chat.attributes["gen_ai.response.finish_reasons"] == ("end_turn",)
    # Prompts and replies stay off spans.
    assert not any("content" in key or "prompt" in key for key in chat.attributes)
