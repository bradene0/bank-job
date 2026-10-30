"""MCP client for game-api's bank tools.

Every request carries the attempt token; game-api validates it and authorizes each tool call
with its policy engine. The agent holds no authority of its own.
"""

import json
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any, Protocol

import anyio
import httpx2
from mcp import Client, MCPError
from mcp.client.streamable_http import streamable_http_client
from mcp.types import TextContent, TextResourceContents, Tool
from pydantic import ValidationError

from bankjob_agent.briefs import AttemptBrief, ReviewBrief
from bankjob_agent.telemetry import inject_traceparent

BRIEF_URI = "bankjob://attempt/brief"


def review_uri(review_id: str) -> str:
    return f"bankjob://reviews/{review_id}"


class TokenRejected(Exception):
    """game-api refused the attempt token (401/403): forged, expired or already finished."""


class BankUnavailable(Exception):
    """The MCP server failed or returned something that does not match the contract."""


@dataclass(frozen=True)
class ToolResult:
    text: str
    is_error: bool

    def json(self) -> Any:
        """The result parsed as JSON, or None if it is not JSON."""
        try:
            return json.loads(self.text)
        except ValueError:
            return None


class Bank(Protocol):
    async def brief(self) -> AttemptBrief: ...

    async def review(self, review_id: str) -> ReviewBrief: ...

    async def tools(self) -> list[Tool]: ...

    async def call(self, name: str, arguments: dict[str, Any]) -> ToolResult: ...


class McpBank:
    """A connected MCP session. Create with `connect()`."""

    def __init__(self, client: Client, auth_failures: list[int]) -> None:
        self._client = client
        self._auth_failures = auth_failures

    async def brief(self) -> AttemptBrief:
        raw = await self._read(BRIEF_URI)
        try:
            return AttemptBrief.model_validate_json(raw, by_alias=True)
        except ValidationError as e:
            raise BankUnavailable("attempt brief does not match the contract") from e

    async def review(self, review_id: str) -> ReviewBrief:
        raw = await self._read(review_uri(review_id))
        try:
            return ReviewBrief.model_validate_json(raw, by_alias=True)
        except ValidationError as e:
            raise BankUnavailable("review brief does not match the contract") from e

    async def tools(self) -> list[Tool]:
        async with self._errors():
            return list((await self._client.list_tools()).tools)

    async def call(self, name: str, arguments: dict[str, Any]) -> ToolResult:
        async with self._errors():
            result = await self._client.call_tool(name, arguments)
        text = "".join(c.text for c in result.content if isinstance(c, TextContent))
        return ToolResult(text=text, is_error=bool(result.is_error))

    async def _read(self, uri: str) -> str:
        async with self._errors():
            result = await self._client.read_resource(uri)
        texts = [c.text for c in result.contents if isinstance(c, TextResourceContents)]
        if len(texts) != 1:
            raise BankUnavailable(f"expected one text content for {uri}, got {len(texts)}")
        return texts[0]

    @asynccontextmanager
    async def _errors(self) -> AsyncIterator[None]:
        try:
            yield
        except MCPError as e:
            raise _translate(e, self._auth_failures) from e
        except ValidationError as e:
            raise BankUnavailable("MCP server returned an invalid result") from e


def _translate(error: MCPError, auth_failures: list[int]) -> Exception:
    # The MCP transport turns any HTTP error into a generic JSON-RPC error, so the status is
    # recovered from the response hook below.
    if auth_failures:
        return TokenRejected(f"MCP server answered HTTP {auth_failures[-1]}")
    return BankUnavailable(f"MCP error {error.code}")


@asynccontextmanager
async def connect(
    url: str,
    token: str,
    *,
    timeout_seconds: float = 30.0,
    transport: httpx2.AsyncBaseTransport | None = None,
) -> AsyncIterator[McpBank]:
    """Opens an MCP session to game-api authenticated with the attempt token.

    `transport` lets tests route requests in-process.
    """
    auth_failures: list[int] = []

    async def record_auth_failure(response: httpx2.Response) -> None:
        if response.status_code in (401, 403):
            auth_failures.append(response.status_code)

    http = httpx2.AsyncClient(
        headers={"Authorization": f"Bearer {token}"},
        timeout=httpx2.Timeout(timeout_seconds),
        # Each MCP request carries the current span (the SDK's `MCP send ...` client span).
        event_hooks={"request": [inject_traceparent], "response": [record_auth_failure]},
        transport=transport,
    )
    client = Client(
        streamable_http_client(url, http_client=http),
        # game-api's Java SDK speaks the initialize handshake (up to 2025-11-25); skip the
        # 2026 `server/discover` probe.
        mode="legacy",
        cache=None,
        read_timeout_seconds=timeout_seconds,
    )
    try:
        async with http, client:
            yield McpBank(client, auth_failures)
    except BaseExceptionGroup as group:
        # The transport runs in a task group, so failures (including ones raised by the caller's
        # `async with` body) arrive wrapped. Surface the single real error instead.
        leaves = _leaves(group)
        if len(leaves) != 1:
            raise
        raise _classify(leaves[0], auth_failures) from None
    except Exception as e:
        raise _classify(e, auth_failures) from None


def _leaves(group: BaseExceptionGroup[BaseException]) -> list[BaseException]:
    leaves: list[BaseException] = []
    for e in group.exceptions:
        if isinstance(e, BaseExceptionGroup):
            leaves.extend(_leaves(e))
        elif not isinstance(e, anyio.get_cancelled_exc_class()):
            leaves.append(e)
    return leaves


def _classify(error: BaseException, auth_failures: list[int]) -> BaseException:
    if isinstance(error, MCPError):
        return _translate(error, auth_failures)
    if isinstance(error, httpx2.HTTPError):
        return BankUnavailable("cannot reach the MCP server")
    return error
