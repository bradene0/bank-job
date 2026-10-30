# Testing strategy

The goal is to show testing well beyond unit tests. Each layer below runs in CI unless
marked otherwise.

| Layer | Tooling | What it proves |
|---|---|---|
| Unit | JUnit 5, pytest, Vitest | Logic in isolation |
| Property-based | jqwik (ledger), Hypothesis (agent parsing) | Ledger invariants hold for any sequence of valid operations |
| Integration | Testcontainers (real Postgres + Kafka) | Migrations, constraint triggers, outbox, consumers work against real infrastructure |
| Concurrency | JUnit + parallel executors | No lost updates or overdrafts under contention |
| Architecture | ArchUnit | Module boundaries hold (e.g., only the ledger module writes postings) |
| API contract | Schemathesis against `openapi.yaml` | The API matches its spec and handles unexpected input |
| Event contract | JSON Schema (`docs/contracts/events`) in `AttemptEventsIT` | Every event an attempt publishes matches its schema and carries the attempt's trace |
| Failure injection | Testcontainers (`docker pause` on Kafka mid-transfer, forced re-sends, poison messages); compose (`docker compose stop kafka` mid-game) | Outbox recovers; no lost or duplicated effects (projections match the ledger); poison messages go to the DLQ without blocking their partition; replay fallback triggers |
| Tracing | `infra/compose/check_trace.py` in CI | One attempt is one connected trace across game-api, the agent and every Kafka consumer, and `GET /v1/traces` returns it |
| End-to-end | Playwright against the compose stack (desktop Chromium, Pixel 7 emulation) | A player wins Level 1 in under two minutes by mouse drag, keyboard only and taps on a phone; replay mode plays recordings while the API is cut off; the "Under the hood" trace spans game-api and the agent |
| Accessibility | axe (via Playwright), WCAG 2.2 AA tags | No violations on the board, a result and the open "Under the hood" panel, in both themes and on a phone |
| Content leak | `pnpm check:bundle` | No prompt fragment from `content/tiles.yaml` is in the built game (ADR-0003) |
| LLM evals | `services/evals` | Attack matrix results, guardrail regressions, Penny behavior |
| Load | k6 (stub LLM) | Throughput and p95/p99 latency against the SLOs |
| Infrastructure | `terraform validate`, `terraform plan`, tflint | IaC is valid and reviewable |

## LLM evals specifics

- Deterministic grading first: the ledger diff and policy decisions decide the outcome.
- LLM-as-judge only for qualitative checks (e.g., "did Penny stay in character").
- Stub mode (`LLM_MODE=stub`) returns scripted teller behavior for fast, free tests.
- Nightly: random 10% sample of combos per level (live). Full matrix: on changes to
  `content/`, prompts or policy code. Use the Message Batches API for full runs.
- Every combo that ever won becomes a pinned regression case with its expected outcome.
