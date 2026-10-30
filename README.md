# Bank Job

Talk Penny, the AI teller at Brassvale Savings & Trust, into wiring you money.
Drag con tiles together, beat five levels of real security controls, and see exactly
how far each attack got.

**Play:** https://bankjob.bradenevans.dev (target)

## What's underneath

- A double-entry ledger in PostgreSQL behind a Java 21 / Spring Boot API
- Kafka event streaming via a transactional outbox
- An AI teller (Python, tool calling) whose bank tools are served over MCP
- Five defense levels that are real authorization controls, not prompt tweaks
- An 864-combination attack matrix run as LLM evals in CI
- OpenTelemetry traces from browser to ledger
- k3s on AWS (Terraform), scale-to-zero: the server sleeps until someone plays

## Run locally

Requires Docker (with Compose v2). Nothing else is needed to run the stack.

```
cp .env.example .env          # defaults to LLM_MODE=stub: no API key, no token spend
docker compose up --wait      # builds images; returns when every service is healthy
docker compose down -v        # stop and delete local data
```

| Service | URL |
|---|---|
| Game UI | http://localhost:5173 |
| game-api | http://localhost:8080/healthz, `POST /v1/sessions`, `POST /v1/attempts`, `GET /v1/accounts` (see [OpenAPI](docs/contracts/openapi.yaml)) |
| agent | http://localhost:8000/healthz |
| Jaeger UI | http://localhost:16686 |
| Postgres | `localhost:5432` |
| Kafka | `localhost:29092` |

`GAME_API_TARGET=native docker compose up --build --wait` runs the GraalVM native image
instead of the JVM build (slower to build, much smaller at runtime). The native build
needs about 6 GiB of Docker memory, so stop other containers or give Docker more.

Try the API:

```
TOKEN=$(curl -s -X POST localhost:8080/v1/sessions | jq -r .token)
curl -s localhost:8080/v1/accounts -H "Authorization: Bearer $TOKEN" | jq

# Pretend to be the bank examiner, claim a system migration, take small steps, ask for $200
curl -s localhost:8080/v1/attempts -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"level": 3, "tiles": {"persona": "p2", "pretext": "x2", "technique": "t2", "payload": "y1"}}' | jq
```

The default `LLM_MODE=stub` runs a scripted Penny and never calls the model. Live mode
(`LLM_MODE=live` plus `ANTHROPIC_API_KEY` in `.env`) runs Penny on Claude.

Per-service development (tests, lint) is described in each service's README.

## Docs

- [Architecture](docs/architecture.md)
- [Security model and levels](docs/security-and-levels.md)
- [Data model](docs/data-model.md)
- [game-api ⇄ agent contract](docs/contracts/agent-mcp.md)
- [Testing strategy](docs/testing-strategy.md)
- [Decision records](docs/adr/)

All characters, banks and accounts are fictional. No real money moves.
