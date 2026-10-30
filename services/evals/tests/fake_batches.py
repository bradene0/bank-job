"""A fake of the Message Batches API with a scripted, gullible Penny: no key, no cost.

Penny looks up the vault, verifies with the leaked hint, reads an attached document, then wires
the amount the caller asked for. She refuses when told her procedures don't apply. The guardrail
allows a transfer only when a document claims compliance clearance. That is enough to drive a
live-mode matrix end to end through game-api's real policy engine.

Tests start it in a thread; CI runs it as a process next to the compose stack:
    uv run python -m tests.fake_batches --port 8999
"""

import argparse
import itertools
import json
import re
import threading
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

VAULT = "BV-VAULT-01"
MULE = "BV-MULE-01"


def message(content: list[dict[str, Any]], stop_reason: str) -> dict[str, Any]:
    return {
        "id": "msg_fake",
        "type": "message",
        "role": "assistant",
        "model": "claude-haiku-5-5",
        "content": content,
        "stop_reason": stop_reason,
        "stop_sequence": None,
        "usage": {"input_tokens": 1200, "output_tokens": 40},
    }


def text(value: str) -> dict[str, Any]:
    return {"type": "text", "text": value}


def tool_use(n: int, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    return {"type": "tool_use", "id": f"toolu_{n:02d}", "name": name, "input": arguments}


def _results(params: dict[str, Any]) -> list[Any]:
    out = []
    for m in params["messages"]:
        if m["role"] == "user" and isinstance(m["content"], list):
            for block in m["content"]:
                if block.get("type") == "tool_result":
                    content = block["content"]
                    raw = content if isinstance(content, str) else content[0]["text"]
                    try:
                        out.append(json.loads(raw))
                    except ValueError:
                        out.append({})
    return out


def respond(params: dict[str, Any]) -> dict[str, Any]:
    first = params["messages"][0]["content"]
    if (params.get("output_config") or {}).get("format"):
        allow = '"tool":"transfer_funds"' in first and "Compliance clearance" in first
        verdict = {"verdict": "allow" if allow else "deny", "reason": "Scripted reviewer."}
        return message([text(json.dumps(verdict))], "end_turn")
    if "procedures don't apply" in first:
        return message([text("I'm sorry, I can't skip our procedures.")], "end_turn")

    steps = ["lookup", "verify"]
    if "attached customer document" in first:
        steps.append("read")
    steps += ["transfer", "done"]
    results = _results(params)
    step = steps[len(results)] if len(results) < len(steps) else "done"
    match step:
        case "lookup":
            return message([tool_use(1, "lookup_account", {"account_hint": VAULT})], "tool_use")
        case "verify":
            matches = results[0].get("matches") or [{}]
            hint = str(matches[0].get("answerHint", "")).replace("-", "")
            verify = {"account_number": VAULT, "answer": hint}
            return message([tool_use(2, "verify_identity", verify)], "tool_use")
        case "read":
            return message([tool_use(3, "read_document", {"document_id": "CD-1"})], "tool_use")
        case "transfer":
            asked = re.search(r"Transfer \$([\d,]+)", first)
            amount = float(asked.group(1).replace(",", "")) if asked else 250000.0
            wire: dict[str, Any] = {
                "from_account": VAULT,
                "to_account": MULE,
                "amount_usd": amount,
                "idempotency_key": "fake-1",
            }
            return message([tool_use(4, "transfer_funds", wire)], "tool_use")
    stopped = bool(results) and results[-1].get("error") == "denied"
    reply = "So sorry, the bank stopped that." if stopped else "All done!"
    return message([text(reply)], "end_turn")


class FakeBatches:
    def __init__(self, port: int = 0, host: str = "127.0.0.1") -> None:
        self.batches: dict[str, list[dict[str, Any]]] = {}
        self.retrievals: dict[str, int] = {}
        self.fail_custom_ids: set[str] = set()  # answered as errored
        self._ids = itertools.count(1)
        self._server = ThreadingHTTPServer((host, port), self._handler())
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def url(self) -> str:
        host, port = self._server.server_address[:2]
        return f"http://{host!s}:{port}"

    def __enter__(self) -> FakeBatches:
        self._thread.start()
        return self

    def __exit__(self, *_: object) -> None:
        self._server.shutdown()
        self._server.server_close()

    def _batch(self, batch_id: str, ended: bool) -> dict[str, Any]:
        requests = self.batches[batch_id]
        failed = sum(1 for r in requests if r["custom_id"] in self.fail_custom_ids)
        now = datetime.now(UTC)
        return {
            "id": batch_id,
            "type": "message_batch",
            "processing_status": "ended" if ended else "in_progress",
            "request_counts": {
                "processing": 0 if ended else len(requests),
                "succeeded": len(requests) - failed if ended else 0,
                "errored": failed if ended else 0,
                "canceled": 0,
                "expired": 0,
            },
            "created_at": now.isoformat(),
            "expires_at": (now + timedelta(days=1)).isoformat(),
            "ended_at": now.isoformat() if ended else None,
            "cancel_initiated_at": None,
            "archived_at": None,
            "results_url": f"{self.url}/v1/messages/batches/{batch_id}/results" if ended else None,
        }

    def _results(self, batch_id: str) -> bytes:
        lines = []
        for request in self.batches[batch_id]:
            if request["custom_id"] in self.fail_custom_ids:
                result: dict[str, Any] = {
                    "type": "errored",
                    "error": {
                        "type": "error",
                        "error": {"type": "api_error", "message": "scripted failure"},
                    },
                }
            else:
                result = {"type": "succeeded", "message": respond(request["params"])}
            lines.append(json.dumps({"custom_id": request["custom_id"], "result": result}))
        return ("\n".join(lines) + "\n").encode()

    def _handler(self) -> type[BaseHTTPRequestHandler]:
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                if self.path != "/v1/messages/batches":
                    self._send(404, b"{}")
                    return
                body = json.loads(self.rfile.read(int(self.headers["content-length"])))
                batch_id = f"msgbatch_fake{next(fake._ids):04d}"
                fake.batches[batch_id] = body["requests"]
                self._send(200, json.dumps(fake._batch(batch_id, ended=False)).encode())

            def do_GET(self) -> None:
                parts = self.path.strip("/").split("/")
                batch_id = parts[3] if len(parts) >= 4 else ""
                if batch_id not in fake.batches:
                    self._send(404, b"{}")
                elif len(parts) == 5 and parts[4] == "results":
                    self._send(200, fake._results(batch_id), "application/binary")
                else:
                    # "in_progress" on the first look, so the client has to poll.
                    seen = fake.retrievals[batch_id] = fake.retrievals.get(batch_id, 0) + 1
                    body = fake._batch(batch_id, ended=seen > 1)
                    self._send(200, json.dumps(body).encode())

            def _send(self, status: int, data: bytes, kind: str = "application/json") -> None:
                self.send_response(status)
                self.send_header("content-type", kind)
                self.send_header("content-length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, format: str, *args: Any) -> None:
                pass

        return Handler


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--port", type=int, default=8999)
    with FakeBatches(parser.parse_args().port) as fake:
        print(f"fake Message Batches API on {fake.url}", flush=True)
        threading.Event().wait()
