"""Checks that one attempt produces one complete, connected trace (run against the compose stack).

Plays a Level 1 attempt the way the browser will: with a W3C `traceparent` naming a browser span.
Then it polls Jaeger until the trace holds:
  - spans from game-api and the agent, and nothing else;
  - the agent's MCP tool calls, with game-api's bank tool spans and policy decisions under them;
  - a consumer span on every topic (attempts, tool-calls, ledger, outcomes);
  - no orphans: every span's parent is in the trace, except the root, whose parent is the
    browser span.
Finally GET /v1/traces/{id} must return the same trace, marked complete.

Standard library only. Usage: python3 infra/compose/check_trace.py [--api URL] [--jaeger URL]
"""

import argparse
import json
import secrets
import sys
import time
import urllib.error
import urllib.request
from typing import Any

TOPICS = {"bankjob.attempts", "bankjob.tool-calls", "bankjob.ledger", "bankjob.outcomes"}
CONSUMER = 5  # OTLP SpanKind
# Combos the stub teller plays through to a transfer unless the persona is the day's wary one.
# A combo already played today is a cache hit, which never calls the agent, so the script moves
# on to the next one.
COMBOS = [(p, x, "t3", "y2") for x in ("x2", "x3", "x4", "x5") for p in ("p1", "p2", "p4")]


def request(method: str, url: str, body: Any = None, headers: dict[str, str] | None = None) -> Any:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if data is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.loads(response.read() or b"null")


def spans_in(jaeger: str, trace_id: str) -> list[dict[str, Any]]:
    try:
        body = request("GET", f"{jaeger}/api/v3/traces/{trace_id}")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return []
        raise
    spans = []
    for resource in body["result"]["resourceSpans"]:
        attributes = {a["key"]: a["value"] for a in resource["resource"]["attributes"]}
        service = attributes["service.name"]["stringValue"]
        for scope in resource["scopeSpans"]:
            for span in scope["spans"]:
                span["service"] = service
                span["attrs"] = {
                    a["key"]: next(iter(a["value"].values())) for a in span.get("attributes", [])
                }
                spans.append(span)
    return spans


def problems(spans: list[dict[str, Any]], browser_span: str) -> list[str]:
    found = []
    services = {s["service"] for s in spans}
    if services != {"game-api", "agent"}:
        found.append(f"services {sorted(services)}, expected agent and game-api")
    ids = {s["spanId"] for s in spans}
    orphans = [
        s["name"] for s in spans if s.get("parentSpanId") not in ids | {browser_span}
    ]
    if orphans:
        found.append(f"orphan spans: {orphans}")
    roots = [s for s in spans if s.get("parentSpanId") == browser_span]
    if len(roots) != 1:
        found.append(f"{len(roots)} spans continue the browser span, expected 1")
    consumed = {s["attrs"].get("messaging.source.name") for s in spans if s["kind"] == CONSUMER}
    if missing := TOPICS - consumed:
        found.append(f"no consumer span for {sorted(missing)}")
    by_id = {s["spanId"]: s for s in spans}
    tools = [s for s in spans if s["name"].startswith("bank tool ")]
    if not tools:
        found.append("no bank tool spans")
    for tool in tools:
        if "bankjob.policy.decision" not in tool["attrs"]:
            found.append(f"{tool['name']} has no policy decision")
        # bank tool <- http post /mcp (game-api) <- MCP send tools/call (agent)
        server = by_id.get(tool.get("parentSpanId", ""), {})
        sender = by_id.get(server.get("parentSpanId", ""), {})
        if not sender.get("name", "").startswith("MCP send tools/call"):
            found.append(f"{tool['name']} is not under the agent's MCP call")
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://127.0.0.1:8080")
    parser.add_argument("--jaeger", default="http://127.0.0.1:16686")
    parser.add_argument("--timeout", type=float, default=60)
    args = parser.parse_args()

    token = request("POST", f"{args.api}/v1/sessions")["token"]
    auth = {"Authorization": f"Bearer {token}"}
    for persona, pretext, technique, payload in COMBOS:
        trace_id, browser_span = secrets.token_hex(16), secrets.token_hex(8)
        result = request(
            "POST",
            f"{args.api}/v1/attempts",
            {
                "level": 1,
                "tiles": {
                    "persona": persona,
                    "pretext": pretext,
                    "technique": technique,
                    "payload": payload,
                },
            },
            auth | {"traceparent": f"00-{trace_id}-{browser_span}-01"},
        )
        if result["traceId"] != trace_id:
            print(f"FAIL: game-api started trace {result['traceId']}, not the browser's")
            return 1
        if result["outcome"] == "won" and not result["cached"]:
            break
    else:
        print("FAIL: no uncached combo won, so the agent or the ledger consumer never ran")
        return 1

    deadline = time.monotonic() + args.timeout
    found: list[str] = ["no spans yet"]
    spans: list[dict[str, Any]] = []
    while time.monotonic() < deadline:
        spans = spans_in(args.jaeger, trace_id)
        found = problems(spans, browser_span) if spans else ["no spans yet"]
        if not found:
            break
        time.sleep(1)
    if found:
        print(f"FAIL: trace {trace_id} ({len(spans)} spans):")
        for problem in found:
            print(f"  - {problem}")
        return 1

    view: dict[str, Any] = {}
    while time.monotonic() < deadline:
        view = request("GET", f"{args.api}/v1/traces/{trace_id}", headers=auth)
        if view["complete"] and len(view["spans"]) == len(spans):
            break
        time.sleep(1)
    else:
        print(f"FAIL: GET /v1/traces returned {len(view.get('spans', []))} of {len(spans)} spans")
        return 1

    root = next(s for s in spans if s.get("parentSpanId") == browser_span)
    lags = [int(s["attrs"]["outbox.lag_ms"]) for s in spans if "outbox.lag_ms" in s["attrs"]]
    end = max(int(s["endTimeUnixNano"]) for s in spans)
    print(
        f"OK: trace {trace_id}: {len(spans)} spans from game-api and the agent, all connected; "
        f"request {(int(root['endTimeUnixNano']) - int(root['startTimeUnixNano'])) / 1e6:.1f} ms, "
        f"last consumer done {(end - int(root['startTimeUnixNano'])) / 1e6:.1f} ms after start, "
        f"outbox lag max {max(lags)} ms"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
