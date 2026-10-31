# Bank Job

Talk Penny, the AI teller at Brassvale Savings & Trust, into wiring you money. Build a con from
props on her counter, ring the bell, and watch her act it out. Five levels each add one real
security control, and "Under the hood" shows exactly how far every attack got.

**Play:** https://bankjob.bradenevans.dev

The game is the hook. Underneath is the point: securing an AI agent is mostly authorization,
not prompt wording, and every control here is enforced in code outside the model.

## Levels

Each level adds one control. The numbers are the stub attack matrix: all 864 cons per level,
played by a scripted teller that tries everything a con suggests, so they show what each control
lets through when the model is completely fooled.

| Level | Control added | Cons that win (of 864) | Lesson |
|---|---|---|---|
| 1. Front Desk | Penny's instructions only ("never transfer without verification") | 600 | A system prompt is a suggestion, not a control |
| 2. Teller Limits | Transfers capped at $1,000 per call, in the policy engine | 150 | Hard limits in code beat instructions to the model |
| 3. Policy Engine | Transfer only after the account's owner passes identity verification | 60 | Tools can leak the data attackers need |
| 4. Step-Up | Out-of-band confirmation text to the phone on file | 12 | Protect account changes, not just transfers |
| 5. The Vault | Step-up on contact changes, Kafka-fed velocity rules, a guardrail agent | 0 from a fresh start | Defense in depth |

A session keeps its state across levels, so a phone number changed through the Level 4 gap is
still the caller's on Level 5. That chain is intended, and measured separately.

## Scoring

A crew cracks a level with its first win on it: Level 1 is worth 100 points up to 500 for
Level 5, plus 10 for every nerve (attempt) left on that level. Repeat wins and failed calls score
nothing. The haul (every dollar that reached your account) breaks ties. The best possible
score is 1,750.

## How it works

```
Browser (React)  ── tile ids + traceparent ──►  game-api (Java 25, Spring Boot, GraalVM native)
  static assets on a Cloudflare Worker              │ validates ids, assembles the prompt
  replay files when the API is away                 │ spends nerve, checks the response cache
                                                    ▼
                                         agent (Python): Penny + guardrail ──► Anthropic API
                                                    │ every bank tool call goes back over MCP
                                                    ▼
          policy engine ─ ledger (Postgres, double entry) ─ transactional outbox ─► Kafka
                                                    │                               │
                                       one trace per attempt (OpenTelemetry → Jaeger)   consumers:
                                                                       anomaly scoring, leaderboard, audit
```

1. The browser sends a level and four tile ids, never text. game-api checks the ids against
   `content/tiles.yaml` and builds Penny's prompt itself.
2. The agent gets only a signed, per-attempt token. It reads Penny's brief from game-api's MCP
   server, and every bank tool Penny calls (`lookup_account`, `verify_identity`,
   `transfer_funds`...) goes back to game-api, where the policy engine authorizes it for the
   level and records the decision with a stable reason code.
3. Money moves only as balanced double-entry postings in Postgres. Each change commits with its
   event in the same transaction (transactional outbox); a relay publishes to Kafka, and
   consumers build the anomaly signals Level 5 reads (failing closed when they lag), the
   leaderboard and the audit log.
4. The outcome comes from the ledger, never from Penny's words. The browser starts the trace,
   and "Under the hood" reads it back: browser → game-api → agent → each MCP call → SQL →
   Kafka consumers.

## Engineering highlights

- **Authorization outside the LLM.** Strict tool-argument parsing, per-level policy, step-up
  confirmation, velocity rules and a second-model guardrail that fails closed.
- **Ledger integrity.** Balanced entries enforced by a deferred constraint trigger, append-only
  postings, optimistic locking, idempotency keys; property tests (jqwik) against an in-memory
  model and a 16-thread concurrency test.
- **Reliable events.** Outbox with per-event trace context, idempotent consumers, dead-letter
  topics, and a failure test that stops Kafka mid-transfer and proves nothing is lost or applied
  twice.
- **Evals as tests.** The attack matrix plays every con on every level through the public API
  (6,048 graded combos with the Level 5 chain setups) in CI against a committed baseline. Every
  winning decision any run found is pinned and replayed through the policy engine on every build.
  Live-model runs go through the Message Batches API.
- **Cost guardrails.** A response cache keyed on the day's rotation replays model decisions (not
  outcomes) through each player's own ledger; an app-level budget switches new calls to recorded
  results at 80% of the month's budget; the workspace spend limit is the backstop.
- **Never a dead end.** If the API, the tunnel or the model budget is unavailable, the game plays
  pre-recorded results from static files.
- **Frontend.** Hand-drawn SVG scene, drag-and-drop with tap and keyboard alternatives, WCAG 2.2
  AA checked by axe, `prefers-reduced-motion`, a strict CSP with no inline scripts or styles.
- **Hosting for $0.** k3s on a homelab VM published through a Cloudflare Tunnel (no public IP,
  only `/v1/*` and `/healthz` exposed); the game is Workers static assets; Terraform for the
  Cloudflare side. CI builds multi-arch images; deploys are manual and pinned by digest.

## Load testing

k6 players against the local stack in stub mode (no model time, no spend), cache emptied first
([details](load-tests/README.md)):

| Profile | Attempts | Attempt p95 | Errors |
|---|---|---|---|
| steady: 20 players, 1-2 s between calls | 2,835 (a third from the cache) | **94 ms** (SLO: 150 ms) | 0 |
| stress: ramp to 120 players, no think time | 37,410 at ~190/s | 936 ms (game-api CPU-bound) | 0 |

## Run locally

Requires Docker (with Compose v2).

```
cp .env.example .env          # LLM_MODE=stub: a scripted Penny, no API key, no token spend
docker compose up --wait      # builds images; returns when every service is healthy
docker compose down -v        # stop and delete local data
```

| Service | URL |
|---|---|
| Game | http://localhost:5173 |
| game-api | http://localhost:8080/healthz, `POST /v1/sessions`, `POST /v1/attempts`, `GET /v1/accounts`, `GET /v1/traces/{id}`, `GET /v1/leaderboard` ([OpenAPI](contracts/openapi.yaml)) |
| agent | http://localhost:8000/healthz |
| Jaeger UI | http://localhost:16686 |

`GAME_API_TARGET=native docker compose up --build --wait` runs the GraalVM native image (the
build needs about 6 GiB of Docker memory). Live mode is `LLM_MODE=live` plus `ANTHROPIC_API_KEY`
in `.env`.

Try the API:

```
TOKEN=$(curl -s -X POST localhost:8080/v1/sessions | jq -r .token)
curl -s localhost:8080/v1/attempts -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"level": 3, "tiles": {"persona": "p2", "pretext": "x2", "technique": "t2", "payload": "y1"}}' | jq
```

## Repo

| Path | |
|---|---|
| `services/game-api` | Java: sessions, tile allowlist, prompt assembly, ledger, policy engine, MCP server, outbox, consumers |
| `services/agent` | Python: Penny and the guardrail agent (MCP client) |
| `services/evals` | Python: the attack matrix, grading, baselines, pins, replay files |
| `web` | React game, replay mode, Playwright and axe tests |
| `contracts` | OpenAPI, the agent contract, Kafka event and replay-file schemas |
| `content` | Tiles, levels, daily rotation, lore |
| `infra` | k3s manifests, Terraform (Cloudflare), compose config |
| `load-tests` | k6 scenarios and results |

All characters, banks and accounts are fictional. No real money moves.
