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

```
cp .env.example .env   # add your own ANTHROPIC_API_KEY, or set LLM_MODE=stub
docker compose up
```

(Exact commands are finalized in milestone M0.)

## Docs

- [Architecture](docs/architecture.md)
- [Security model and levels](docs/security-and-levels.md)
- [Data model](docs/data-model.md)
- [Testing strategy](docs/testing-strategy.md)
- [Decision records](docs/adr/)

All characters, banks and accounts are fictional. No real money moves.
