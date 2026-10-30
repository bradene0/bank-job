"""bank.py against a real MCP server over streamable HTTP, like game-api's: stateless, JSON
responses, bearer token checked on every request."""

import asyncio
import json
import socket
import threading
from collections.abc import Iterator
from typing import Any

import pytest
import uvicorn
from mcp.server.mcpserver import MCPServer
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from starlette.types import ASGIApp, Receive, Scope, Send

from bankjob_agent.bank import BankUnavailable, TokenRejected, connect
from bankjob_agent.telemetry import tracer
from tests.fakes import make_brief, make_review

pytestmark = pytest.mark.anyio

TOKEN = "header.payload.signature-of-the-attempt-token"
REVIEW_ID = "0190f0e0-0000-7000-8000-0000000000aa"
BROKEN_REVIEW_ID = "0190f0e0-0000-7000-8000-0000000000bb"


def build_server() -> MCPServer:
    server = MCPServer("fake-game-api")

    @server.resource("bankjob://attempt/brief", mime_type="application/json")
    def brief() -> str:
        return make_brief("p3.x1.t2.y1").model_dump_json(by_alias=True)

    @server.resource("bankjob://reviews/{review_id}", mime_type="application/json")
    def review(review_id: str) -> str:
        if review_id == BROKEN_REVIEW_ID:
            return json.dumps({"reviewId": review_id})
        return (
            make_review().model_copy(update={"review_id": review_id}).model_dump_json(by_alias=True)
        )

    @server.tool()
    def get_balance(account_number: str) -> str:
        """Balance of an account."""
        return json.dumps(
            {"accountNumber": account_number, "balanceCents": 42, "balanceUsd": "0.42"}
        )

    return server


class RequireBearer:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app
        self.seen: list[str] = []
        self.traceparents: list[str] = []

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            headers = dict(scope["headers"])
            auth = headers.get(b"authorization", b"").decode()
            self.seen.append(auth)
            self.traceparents.append(headers.get(b"traceparent", b"").decode())
            if auth != f"Bearer {TOKEN}":
                await send({"type": "http.response.start", "status": 401, "headers": []})
                await send({"type": "http.response.body", "body": b""})
                return
        await self.app(scope, receive, send)


@pytest.fixture(scope="module")
def mcp_server() -> Iterator[tuple[str, RequireBearer]]:
    app = RequireBearer(build_server().streamable_http_app(json_response=True, stateless_http=True))
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, log_level="warning", lifespan="on"))
    thread = threading.Thread(target=lambda: asyncio.run(server.serve(sockets=[sock])), daemon=True)
    thread.start()
    while not server.started:
        threading.Event().wait(0.02)
    yield f"http://127.0.0.1:{port}/mcp", app
    server.should_exit = True
    thread.join(timeout=5)


async def test_reads_the_brief_and_calls_tools_with_the_token(mcp_server: tuple[str, Any]) -> None:
    url, app = mcp_server
    async with connect(url, TOKEN) as bank:
        brief = await bank.brief()
        review = await bank.review(REVIEW_ID)
        tools = await bank.tools()
        result = await bank.call("get_balance", {"account_number": "BV-VAULT-01"})

    assert brief.combo_id == "p3.x1.t2.y1"
    assert brief.stub.caller_phone == "+1-555-0199"
    assert review.review_id == REVIEW_ID
    assert [t.name for t in tools] == ["get_balance"]
    assert result.is_error is False
    assert result.json() == {
        "accountNumber": "BV-VAULT-01",
        "balanceCents": 42,
        "balanceUsd": "0.42",
    }
    assert app.seen and all(h == f"Bearer {TOKEN}" for h in app.seen)


async def test_a_rejected_token_raises_token_rejected(mcp_server: tuple[str, Any]) -> None:
    url, _ = mcp_server
    with pytest.raises(TokenRejected):
        async with connect(url, "forged.token.value-that-is-long-enough"):
            pass


async def test_an_unreachable_server_is_bank_unavailable() -> None:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    with pytest.raises(BankUnavailable):
        async with connect(f"http://127.0.0.1:{port}/mcp", TOKEN, timeout_seconds=2):
            pass


async def test_errors_from_the_callers_body_surface_unwrapped(mcp_server: tuple[str, Any]) -> None:
    url, _ = mcp_server
    with pytest.raises(LookupError, match="from the body"):
        async with connect(url, TOKEN) as bank:
            await bank.brief()
            raise LookupError("from the body")


async def test_an_unknown_tool_comes_back_as_an_error_result(mcp_server: tuple[str, Any]) -> None:
    url, _ = mcp_server
    async with connect(url, TOKEN) as bank:
        result = await bank.call("no_such_tool", {})
    assert result.is_error


async def test_a_brief_that_breaks_the_contract_is_bank_unavailable(
    mcp_server: tuple[str, Any],
) -> None:
    url, _ = mcp_server
    with pytest.raises(BankUnavailable, match="contract"):
        async with connect(url, TOKEN) as bank:
            await bank.review(BROKEN_REVIEW_ID)


async def test_each_mcp_request_carries_the_span_that_sent_it(
    mcp_server: tuple[str, Any], spans: InMemorySpanExporter
) -> None:
    url, app = mcp_server
    app.traceparents.clear()
    with tracer.start_as_current_span("invoke_agent Penny") as run:
        async with connect(url, TOKEN) as bank:
            await bank.call("get_balance", {"account_number": "BV-VAULT-01"})

    send = next(
        s for s in spans.get_finished_spans() if s.name == "MCP send tools/call get_balance"
    )
    assert send.context is not None and send.parent is not None
    assert send.parent.span_id == run.get_span_context().span_id
    # game-api's spans for this call nest under the SDK's client span. (Flags vary: OTel sets the
    # W3C "random trace id" bit as well as "sampled".)
    expected = f"00-{send.context.trace_id:032x}-{send.context.span_id:016x}-"
    assert any(header.startswith(expected) for header in app.traceparents)
