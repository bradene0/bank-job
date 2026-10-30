# ADR-0015: The browser starts each attempt's trace, without a browser tracing SDK

**Status:** Accepted (2026-10-29)

## Context
The architecture calls for one trace per attempt, starting in the browser. Exporting spans
from a browser needs an OTLP endpoint reachable from the public internet. That means
exposing the collector (Jaeger) or proxying it through game-api: an unauthenticated write
endpoint that anyone could fill with junk spans, on a single small instance. It would also
add the OpenTelemetry web SDK to the page.

## Decision
The browser generates a W3C `traceparent` (random trace id and span id) for each attempt and
sends it with `POST /v1/attempts`. game-api continues that trace (W3C only, no baggage), so
every server span carries the browser's trace id and the root server span's parent is the
browser's span id. The browser does not export a span. Instead, the "Under the hood" panel
shows the round trip it measured itself next to the server-side waterfall it reads from
`GET /v1/traces/{id}`.

## Consequences
- No public telemetry endpoint and no tracing SDK in the page.
- In Jaeger the browser's span appears as the (missing) parent of the root server span.
  `infra/compose/check_trace.py` checks exactly that: one span continues the browser span,
  and every other span's parent is in the trace.
- A client chooses its own trace id. Traces are only readable by the session that made them
  (`GET /v1/traces` checks the attempt's owner), and a random 128-bit id cannot be guessed.
  A client that reuses its own trace id only merges its own attempts.
- If browser-side spans (render time, interaction latency) become worth having, a
  rate-limited OTLP proxy route on game-api is the place to add them.
