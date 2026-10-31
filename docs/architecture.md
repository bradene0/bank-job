# Architecture

## Context

Visitors (mostly recruiters and hiring managers) open the game from `bradenevans.dev`
or directly. They must be playing within seconds, with no login. The backend runs on one
always-on homelab VM behind a Cloudflare Tunnel (ADR-0017), so hosting costs nothing beyond
the Anthropic workspace limit.

## Hostnames

| Host | Serves | Backed by |
|---|---|---|
| `bankjob.bradenevans.dev` | Game UI, replay files | A Cloudflare Worker with static assets (deployed by wrangler) |
| `bankjob-live.bradenevans.dev` | Live API (`/v1/*`, `/healthz`) | The homelab k3s node, through a Cloudflare Tunnel (proxied CNAME to the tunnel) |
| `bradenevans.dev` | Portfolio (separate repo) | Embeds the game via iframe |

DNS is the `bradenevans.dev` Cloudflare zone. The API host is one level deep
(`bankjob-live`, not `live.bankjob`) because the zone's free edge certificate covers
`*.bradenevans.dev` only; a second-level name fails the TLS handshake. Both repos manage their own records with the
Cloudflare Terraform provider; neither creates or deletes the zone. Cloudflare terminates TLS
for both hosts, so the origin needs no certificate.

`cloudflared` runs in the cluster and connects outbound to Cloudflare. The node has no public
IP and no open port: its firewall admits SSH from the owner's admin machine only, and blocks
outbound traffic to every private range. The tunnel forwards only `/v1/*` and `/healthz` to
Traefik (`infra/terraform`), and the Traefik ingress allowlists the same paths, so `/mcp` and
the actuator are never public.

## Containers

```
Browser (React)
  │  1. load UI + replay data from the Worker's static assets (instant)
  │  2. poll live.../healthz; live play when it answers, replay mode when it does not
  ▼
bankjob-live.bradenevans.dev  (Cloudflare edge → tunnel → cloudflared → Traefik, k3s on a homelab VM)
  ├─ game-api (Java, Spring Boot, native image)
  │    ├─ REST API (sessions, attempts, leaderboard)
  │    ├─ Auth: anonymous guest JWT
  │    ├─ Tile allowlist + prompt assembly (from content/tiles.yaml)
  │    ├─ Ledger (double-entry) + policy engine (per-level controls)
  │    ├─ MCP server: bank tools for Penny
  │    ├─ Transactional outbox publisher → Kafka
  │    └─ Kafka consumers: anomaly scoring, leaderboard projection, audit
  ├─ agent (Python): Penny (teller) + guardrail agent; MCP client; calls Anthropic API
  ├─ kafka (single broker, KRaft)
  ├─ postgres (local-path volume on the VM disk)
  ├─ jaeger v2 (also the OTLP collector, ADR-0007; in-memory storage, short retention)
  └─ cloudflared (outbound tunnel to Cloudflare; the only way in)
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
   outcome, the reply, the tool-call timeline and the trace ID. The UI plays the timeline back
   as Penny's performance at her window (one pose per tool call, then the outcome), and its
   "Under the hood" dialog renders the timeline and the trace. If the agent is down or the
   model budget is spent, the response is a 503 with `replay: true`, the nerve is refunded,
   and the UI uses replay mode.

Each attempt is **single-turn**: one composed message, one teller run (which may include
several tool calls). This keeps the attack space enumerable (864 combos per level) and
makes caching and evals exact.

## Deployment and availability

- **Node:** one Ubuntu VM on the owner's Proxmox host (x86_64, 4 vCPU, 8 GiB) runs k3s and
  every workload in `infra/k8s`. NetworkPolicies deny ingress except what each workload needs.
- **Release:** every green CI run on `main` publishes multi-arch images to GHCR. CI never
  reaches the cluster or the Terraform state: the owner deploys a commit from the admin machine
  (`infra/k8s/deploy.sh`, images pinned by digest) and applies Terraform by hand.
- **Secrets:** Kubernetes Secrets (encrypted at rest by k3s), created from the admin machine by
  `infra/k8s/create-secrets.sh`. Nothing secret is in the repo or in CI, except the evals key in
  the `anthropic-evals` environment.
- **Replay mode:** whenever the API does not answer (the host or the tunnel is down), the UI
  serves results from `/replay/{level}/{comboId}.json` (`docs/contracts/replay.schema.json`),
  generated by the eval harness. The game polls `/healthz` and switches to live play as soon as
  it answers. A combo with no recording says so and costs no nerve, so the game never
  dead-ends. A 503 from an attempt (agent down, model budget spent) falls back to the recording
  for that combo.

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
  outbox lag, consumer lag.
- The game UI fetches its own trace by ID to render "Under the hood".

### Initial SLOs (adjust after measurement)

| SLO | Target |
|---|---|
| Availability (the live API; replay mode covers the rest) | 99% |
| p95 `POST /v1/attempts` excluding LLM time (stub mode) | < 150 ms |
| p95 end-to-end attempt with live LLM | < 4 s |

## Eval matrix

`services/evals` plays every combo (864 per level) through the public API like a browser, grades
each from the API's answer, and writes the matrix, a summary and the replay files. Stub mode runs
in CI on every change. Live mode uses the Message Batches API, in rounds:

```
harness ──POST /v1/attempts──► game-api ──► agent (live) ──POST /v1/messages──► memo proxy (in the harness)
   ▲                                │                                            │ known: answer
   │  503 replay:true ◄─────────────┘ ◄── model unavailable ◄──────────────────── │ new: defer
   │
   └── between rounds: deferred calls ──► Message Batches API ──► answers memoized ──► replay the combos
```

The agent never holds the API key in this setup, and every final play is an ordinary attempt
through the policy engine and ledger. Details: `services/evals/README.md`.

## Cost guardrails

- Hosting is free: the homelab VM, the tunnel and the Worker's static assets cost nothing.
- Anthropic: dedicated workspace with a monthly spend limit (set by Braden in the
  Console), plus an app-level token budget counter in Postgres. At ~80% of the budget
  the game switches to replay mode.
- Per-IP rate limits and per-session nerve budgets.
- Load tests run with `LLM_MODE=stub` against the local compose stack, so they never spend
  tokens or load the homelab.
