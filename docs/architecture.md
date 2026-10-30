# Architecture

## Context

Visitors (mostly recruiters and hiring managers) open the game from `bradenevans.dev`
or directly. They must be playing within seconds, with no login. The backend runs on
one EC2 instance that sleeps when idle to keep costs near $17/month.

## Hostnames

| Host | Serves | Backed by |
|---|---|---|
| `bankjob.bradenevans.dev` | Game UI, replay files, `/api/wake` | S3 + CloudFront; `/api/wake` routed to the wake Lambda (Function URL origin) |
| `live.bankjob.bradenevans.dev` | Live API + operator console | EC2 (k3s, Traefik ingress), **proxied through Cloudflare** (orange cloud). Origin IP updated on every boot |
| `bradenevans.dev` | Portfolio (separate repo) | Embeds the game via iframe |

**DNS is Cloudflare, not Route 53.** The domain is registered with Cloudflare Registrar,
which requires Cloudflare's nameservers. Both repos manage their own records in the
`bradenevans.dev` Cloudflare zone with the Cloudflare Terraform provider, using a
zone-scoped API token. Neither repo creates or deletes the zone.

- `bankjob` → CNAME to the CloudFront distribution, **DNS only** (grey cloud), so
  CloudFront terminates TLS with its ACM certificate (validated by CNAME records that
  Terraform creates in Cloudflare).
- `live.bankjob` → A record to the EC2 public IP, **proxied**. Visitors resolve to
  Cloudflare's edge, so an origin IP change on boot takes effect immediately with no
  DNS TTL wait. SSL mode: Full (strict).

## Containers

```
Browser (React)
  │  1. load UI + Level 1 replay data from CloudFront (instant)
  │  2. GET /api/wake ──────────────► Wake Lambda ──► EC2 StartInstances (if stopped)
  │  3. poll live.../healthz until ready, then switch from replay to live
  ▼
live.bankjob.bradenevans.dev  (Cloudflare proxy → EC2 t4g.large, k3s, Traefik)
  ├─ game-api (Java, Spring Boot, native image)
  │    ├─ REST API (sessions, attempts, leaderboard, operator console)
  │    ├─ Auth: anonymous guest JWT, operator OAuth (GitHub), demo read-only role
  │    ├─ Tile allowlist + prompt assembly (from content/tiles.yaml)
  │    ├─ Ledger (double-entry) + policy engine (per-level controls)
  │    ├─ MCP server: bank tools for Penny
  │    ├─ Transactional outbox publisher → Kafka
  │    └─ Kafka consumers: anomaly scoring, leaderboard projection, audit
  ├─ agent (Python): Penny (teller) + guardrail agent; MCP client; calls Anthropic API
  ├─ kafka (single broker, KRaft)
  ├─ postgres (PVC on EBS)
  ├─ jaeger v2 (also the OTLP collector, ADR-0007; in-memory or Badger storage, short retention)
  └─ idle-reaper (CronJob): stops the instance after 30 idle minutes
```

## Request flow: one attempt

1. Browser `POST /v1/attempts` with `{level, tiles: {persona, pretext, technique, payload}}`
   and a W3C `traceparent` header it generated (it starts the trace, ADR-0015). Guest JWT in
   `Authorization`. The game only knows tile ids, labels and level rules: they are bundled at
   build time from `content/`, without the prompt fragments (a build check fails on a leak).
2. game-api validates the body strictly (tile ids against the allowlist, no other fields),
   allows one attempt at a time per session, spends one "nerve" for the level, and checks
   the response cache.
3. Cache hit: game-api replays the cached model decisions through the policy engine and
   this session's ledger without running Penny (ADR-0012; on Level 5 the guardrail reviews a
   call the recorded run never got to). Cache miss: game-api records the attempt, mints an
   attempt token and sends only that token to the agent.
4. The agent reads Penny's brief (system prompt and customer message, both assembled by
   game-api) from game-api's MCP server, then Penny calls bank tools over MCP (ADR-0011).
   **Every tool call is authorized by the game-api policy engine for the attempt's
   level** and recorded with its decision (see `security-and-levels.md`). On Level 5,
   game-api asks the guardrail agent about sensitive calls before allowing them.
5. Each change commits with its event in one Postgres transaction (the transactional
   outbox, ADR-0002): the attempt row with `AttemptSubmitted`, each tool call with
   `ToolCalled` and `PolicyDecided`, a transfer's postings with `TransferPosted`. The outbox
   relay sends committed events to Kafka; consumers update anomaly signals (which Level 5's
   velocity rule reads, ADR-0013), the leaderboard and the audit log. If Kafka is down the
   game keeps working and the events wait in the outbox.
6. game-api decides the outcome from the ledger, filters Penny's reply, and returns the
   outcome, the reply, the tool-call timeline and the trace ID. The UI's "Under the hood"
   panel renders the trace. If the agent is down or the model budget is spent, the
   response is a 503 with `replay: true`, the nerve is refunded, and the UI uses replay
   mode.

Each attempt is **single-turn**: one composed message, one teller run (which may include
several tool calls). This keeps the attack space enumerable (864 combos per level) and
makes caching and evals exact.

## Scale-to-zero

- **Wake:** the wake Lambda checks the instance state and calls `StartInstances` if
  stopped. It is idempotent and rate-limited. It returns `{state}`.
- **Boot:** a systemd unit updates the Cloudflare A record for `live.bankjob...` to the
  new public IP via the Cloudflare API (token in SSM), then k3s starts workloads. Target:
  live in under 2 minutes. Measure and record the actual numbers.
- **TLS:** Cloudflare terminates TLS at the edge. The origin uses a **Cloudflare Origin CA
  certificate** (long-lived, stored in SSM, mounted as a k8s Secret), so there is no
  certificate issuance on boot and no cert-manager. The security group allows 443 only
  from Cloudflare's published IP ranges.
- **Idle:** game-api records last activity. The idle-reaper CronJob calls `StopInstances`
  on its own instance after 30 idle minutes (IAM permission scoped to that instance only).
- **Replay mode:** while asleep or on any failure, the UI serves results from
  `/replay/{level}/{comboId}.json` (`docs/contracts/replay.schema.json`), generated by the
  eval harness. The game polls `/healthz` (every 5 s while waking) and switches to live play
  as soon as it answers. A combo with no recording says so and costs no nerve, so the game
  never dead-ends. A 503 from an attempt (agent down, model budget spent) falls back to the
  recording for that combo.

## Data contracts

- `docs/contracts/attempt-request.schema.json` — the only shape the browser may send
- `docs/contracts/openapi.yaml` — REST API (keep in sync with code; validated in CI)
- `docs/contracts/events/*.schema.json` — Kafka event payloads

Kafka topics: `bankjob.attempts`, `bankjob.tool-calls`, `bankjob.ledger`,
`bankjob.outcomes`, each with a `.dlq` dead-letter topic. Trace context travels in
Kafka headers. Delivery, consumers and failure handling: `docs/contracts/events/README.md`.

## Observability

- One trace per attempt: browser span → game-api → agent → each MCP tool call →
  ledger/DB → outbox → Kafka consumers. The browser does not export its span (no public
  telemetry endpoint, ADR-0015); game-api continues the browser's `traceparent`
  (W3C only, no baggage); the agent continues game-api's, and every MCP request carries
  the agent's span back. Each outbox row stores its writer's trace context, so the relay's
  send (and every consumer) joins the trace even after the HTTP request has returned.
- game-api: Micrometer Tracing with the OpenTelemetry bridge, OTLP/HTTP to Jaeger; one span
  per SQL statement (datasource-micrometer, no parameter values), per bank tool call
  (tagged with the policy decision), per Kafka send and per consumer. Health probes, trace
  polling and database calls outside any operation are not traced. The agent: OTel SDK,
  FastAPI instrumentation, the MCP SDK's client spans, and GenAI `chat <model>` spans with
  token usage (never prompt or reply text).
- `GET /v1/traces/{id}` reads the trace back from Jaeger's query API for its owner only,
  reduced to allowlisted attributes, for the "Under the hood" panel.
- Metrics: request rate/latency/errors per endpoint, LLM latency and token spend,
  outbox lag, consumer lag, wake-to-ready time.
- The game UI fetches its own trace by ID to render "Under the hood".

### Initial SLOs (adjust after measurement)

| SLO | Target |
|---|---|
| Availability while awake | 99% |
| p95 `POST /v1/attempts` excluding LLM time (stub mode) | < 150 ms |
| p95 end-to-end attempt with live LLM | < 4 s |
| Wake-to-ready | < 120 s |

## Cost guardrails

- AWS Budget alarm at $20/month (Terraform).
- Anthropic: dedicated workspace with a monthly spend limit (set by Braden in the
  Console), plus an app-level token budget counter in Postgres. At ~80% of the budget
  the game switches to replay mode.
- Per-IP rate limits and per-session nerve budgets.
- Load tests run with `LLM_MODE=stub` so they never spend tokens.

## Ephemeral load-test environment

`.github/workflows/loadtest.yml` (manual or weekly, requires approval) uses Terraform to
create a short-lived environment (EKS behind a flag, or a second EC2), runs k6 in stub
mode, publishes results to `docs/perf/` and the S3 results path, then destroys it.
Public subnets only; no NAT gateway.
