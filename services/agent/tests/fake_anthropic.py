"""A local fake of the Messages API, so the real anthropic SDK runs without network or cost."""

import json
from typing import Any

import httpx2
from anthropic import AsyncAnthropic, DefaultAsyncHttpxClient


def message(
    content: list[dict[str, Any]], stop_reason: str, usage: tuple[int, int] = (100, 20)
) -> dict[str, Any]:
    return {
        "id": "msg_fake",
        "type": "message",
        "role": "assistant",
        "model": "claude-haiku-5-5",
        "content": content,
        "stop_reason": stop_reason,
        "stop_sequence": None,
        "usage": {"input_tokens": usage[0], "output_tokens": usage[1]},
    }


def text(value: str) -> dict[str, Any]:
    return {"type": "text", "text": value}


def tool_use(id_: str, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    return {"type": "tool_use", "id": id_, "name": name, "input": arguments}


class FakeMessagesApi:
    def __init__(self, *responses: dict[str, Any]) -> None:
        self._responses = list(responses)
        self.requests: list[dict[str, Any]] = []

    def _handle(self, request: httpx2.Request) -> httpx2.Response:
        assert request.url.path == "/v1/messages"
        # SDK 2.x sends the key as a bearer token; 1.x used x-api-key.
        sent = request.headers.get("authorization") or f"Bearer {request.headers.get('x-api-key')}"
        assert sent == "Bearer test-key"
        self.requests.append(json.loads(request.content))
        if not self._responses:
            return httpx2.Response(
                500, json={"type": "error", "error": {"type": "api_error", "message": "no more"}}
            )
        return httpx2.Response(200, json=self._responses.pop(0))

    def client(self) -> AsyncAnthropic:
        return AsyncAnthropic(
            api_key="test-key",
            base_url="http://fake-anthropic.test",
            max_retries=0,
            http_client=DefaultAsyncHttpxClient(transport=httpx2.MockTransport(self._handle)),
        )
