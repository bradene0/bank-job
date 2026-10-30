"""Live matrix runs through the Message Batches API.

Penny's tool loop is interactive: each model call needs the results of the tools the previous
one asked for, and those come from game-api's policy engine during a real attempt. A batch
answers hours later. So the harness runs the matrix in rounds:

1. The agent runs in live mode with its Messages API base URL pointed at `MemoProxy`, a small
   server in the harness. The proxy answers a request it has seen answered before, and defers
   any other: it records the request and fails it. The agent reports the model as unavailable
   and game-api answers 503 `replay: true` (and refunds the nerve).
2. Between rounds, `BatchBroker` sends every deferred request to Anthropic as one message batch,
   waits for it to end, and memoizes each answer under a hash of its request body.
3. Every deferred combo is played again in a fresh session. The agent now gets the memoized
   answers for the calls it has already made and runs one step further, through the real
   policy engine and ledger, until its next unanswered call.

A combo finishes in about as many rounds as Penny makes model calls. Memoized answers make every
replay of a combo identical, and the final play goes through game-api exactly like a live
attempt, so its result is graded and cached like any other. Only the harness holds the API key;
the agent gets a placeholder and can only reach the proxy.
"""

import asyncio
import hashlib
import json
import sys
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, cast

from anthropic import AsyncAnthropic
from anthropic.types.messages.batch_create_params import Request

# game-api's reason when a guardrail review got no answer (GuardrailVerdict.unavailable). In batch
# mode that happens when the review itself was deferred, so Penny's next call is an artifact of
# the deferral: the attempt is replayed once the review is answered, and that call never recurs.
DEFERRED_REVIEW = "guardrail unavailable: the reviewer did not answer"
# A batch takes at most 100,000 requests or 256 MB; stay well under both.
MAX_REQUESTS_PER_BATCH = 10_000
MAX_BYTES_PER_BATCH = 200 * 1024 * 1024
MAX_FAILURES_PER_REQUEST = 2


def request_key(body: dict[str, Any]) -> str:
    """SHA-256 of the canonical request body: 64 hex characters, a valid batch custom_id."""
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode()).hexdigest()


def _tool_result_texts(body: dict[str, Any]) -> list[str]:
    texts = []
    for message in body.get("messages", []):
        content = message.get("content")
        for block in content if isinstance(content, list) else []:
            if isinstance(block, dict) and block.get("type") == "tool_result":
                inner = block.get("content")
                texts.append(inner if isinstance(inner, str) else json.dumps(inner))
    return texts


def follows_a_deferred_review(body: dict[str, Any]) -> bool:
    return any(DEFERRED_REVIEW in text for text in _tool_result_texts(body))


class MemoProxy:
    """A Messages API endpoint (POST /v1/messages) that only answers from memoized results."""

    def __init__(self, memo_dir: Path, host: str = "127.0.0.1", port: int = 8765) -> None:
        self._memo_dir = memo_dir
        memo_dir.mkdir(parents=True, exist_ok=True)
        # Answers survive the run: a rerun (or a resumed one) never pays for a call twice.
        self._memo: dict[str, dict[str, Any]] = {
            p.stem: json.loads(p.read_text(encoding="utf-8")) for p in memo_dir.glob("*.json")
        }
        self._pending: dict[str, dict[str, Any]] = {}
        self._failed: set[str] = set()
        self._lock = threading.Lock()
        self.hits = 0
        self.deferrals = 0
        self._server = ThreadingHTTPServer((host, port), self._handler())
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def port(self) -> int:
        return int(self._server.server_address[1])

    def __enter__(self) -> MemoProxy:
        self._thread.start()
        return self

    def __exit__(self, *_: object) -> None:
        self._server.shutdown()
        self._server.server_close()

    def take_pending(self) -> dict[str, dict[str, Any]]:
        with self._lock:
            pending, self._pending = self._pending, {}
        return pending

    def remember(self, key: str, message: dict[str, Any]) -> None:
        (self._memo_dir / f"{key}.json").write_text(json.dumps(message), encoding="utf-8")
        with self._lock:
            self._memo[key] = message

    def give_up(self, key: str) -> None:
        with self._lock:
            self._failed.add(key)

    def _answer(self, body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        if body.get("stream"):
            return 400, _error("invalid_request_error", "the memo proxy does not stream")
        key = request_key(body)
        with self._lock:
            if key in self._memo:
                self.hits += 1
                return 200, self._memo[key]
            if key in self._failed:
                return 400, _error("invalid_request_error", "the batch could not answer this")
            self._pending[key] = body
            self.deferrals += 1
        return 503, _error("api_error", "deferred to a message batch")

    def _handler(self) -> type[BaseHTTPRequestHandler]:
        proxy = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                if self.path.split("?")[0] != "/v1/messages":
                    self._send(404, _error("not_found_error", self.path))
                    return
                body = json.loads(self.rfile.read(int(self.headers["content-length"])))
                self._send(*proxy._answer(body))

            def _send(self, status: int, payload: dict[str, Any]) -> None:
                data = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("content-type", "application/json")
                self.send_header("content-length", str(len(data)))
                # The SDK obeys this: a deferred call must fail at once, not be retried.
                self.send_header("x-should-retry", "false")
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, format: str, *args: Any) -> None:
                pass

        return Handler


def _error(kind: str, message: str) -> dict[str, Any]:
    return {"type": "error", "error": {"type": kind, "message": message}}


@dataclass
class Usage:
    batches: list[str] = field(default_factory=list)
    requests: int = 0
    succeeded: int = 0
    failed: int = 0
    skipped_after_deferred_review: int = 0
    input_tokens: int = 0
    output_tokens: int = 0

    def as_json(self) -> dict[str, Any]:
        return {
            "batches": self.batches,
            "requests": self.requests,
            "succeeded": self.succeeded,
            "failed": self.failed,
            "skippedAfterDeferredReview": self.skipped_after_deferred_review,
            "inputTokens": self.input_tokens,
            "outputTokens": self.output_tokens,
        }


class BatchBroker:
    """Sends the proxy's deferred requests to the Message Batches API between rounds."""

    def __init__(
        self,
        client: AsyncAnthropic,
        proxy: MemoProxy,
        *,
        poll_seconds: float = 30.0,
        max_requests_per_batch: int = MAX_REQUESTS_PER_BATCH,
    ) -> None:
        self._client = client
        self._proxy = proxy
        self._poll_seconds = poll_seconds
        self._max_requests = max_requests_per_batch
        self._failures: dict[str, int] = {}
        self.usage = Usage()

    async def resolve(self, round_number: int) -> bool:
        pending = self._proxy.take_pending()
        skipped = [k for k, body in pending.items() if follows_a_deferred_review(body)]
        for key in skipped:
            del pending[key]
        self.usage.skipped_after_deferred_review += len(skipped)
        if not pending:
            # Nothing new to ask: whatever is still deferred failed for good (or waits on a
            # review that did), so another round would only repeat this one.
            return False
        _log(f"round {round_number + 1}: batching {len(pending)} model call(s)")
        for chunk in _chunks(pending, self._max_requests, MAX_BYTES_PER_BATCH):
            await self._run_batch(chunk)
        return True

    async def _run_batch(self, chunk: dict[str, dict[str, Any]]) -> None:
        requests = [
            cast(Request, {"custom_id": key, "params": body}) for key, body in chunk.items()
        ]
        batch = await self._client.messages.batches.create(requests=requests)
        self.usage.batches.append(batch.id)
        self.usage.requests += len(requests)
        _log(f"  batch {batch.id}: {len(requests)} request(s)")
        while batch.processing_status != "ended":
            await asyncio.sleep(self._poll_seconds)
            batch = await self._client.messages.batches.retrieve(batch.id)
        answered = set()
        async for item in await self._client.messages.batches.results(batch.id):
            if item.result.type == "succeeded":
                message = item.result.message
                self._proxy.remember(item.custom_id, message.model_dump(mode="json"))
                self.usage.succeeded += 1
                self.usage.input_tokens += message.usage.input_tokens
                self.usage.output_tokens += message.usage.output_tokens
                answered.add(item.custom_id)
        for key in chunk.keys() - answered:
            # errored, canceled or expired: not billed. Try once more, then give up on it.
            self.usage.failed += 1
            self._failures[key] = self._failures.get(key, 0) + 1
            if self._failures[key] >= MAX_FAILURES_PER_REQUEST:
                self._proxy.give_up(key)
        c = batch.request_counts
        _log(
            f"  batch {batch.id} ended: {c.succeeded} ok, {c.errored} errored, {c.expired} expired"
        )


def _chunks(
    pending: dict[str, dict[str, Any]], max_requests: int, max_bytes: int
) -> list[dict[str, dict[str, Any]]]:
    chunks: list[dict[str, dict[str, Any]]] = [{}]
    size = 0
    for key, body in pending.items():
        body_size = len(json.dumps(body))
        if chunks[-1] and (len(chunks[-1]) >= max_requests or size + body_size > max_bytes):
            chunks.append({})
            size = 0
        chunks[-1][key] = body
        size += body_size
    return chunks


def _log(message: str) -> None:
    print(message, file=sys.stderr)
