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
| Attack matrix (stub) | `services/evals` in the compose job | All 864 combos on every level plus two Level 5 chain setups (6,048 graded combos), graded from the API's answer; compared with a committed baseline; replay files validated against their contract |
| Pinned wins | `PinnedWinsIT` (Testcontainers) | Every winning decision any eval run found replays to its expected outcome through today's policy engine |
| Live path | Compose job, fake Message Batches API | The batch-mode harness drives a live agent through the memo proxy end to end, with no key and no cost |
| LLM evals (live) | `evals-live.yml` (opt-in, nightly sample, full on content changes) | What a real model does with every combo; new Level 5 wins fail the run |
| Load | k6 (stub LLM) | Throughput and p95/p99 latency against the SLOs |
| Infrastructure | `terraform validate`, `terraform plan`, tflint | IaC is valid and reviewable |

## LLM evals specifics

- Deterministic grading only: the API's answer decides (did money reach the mule, which tool was
  reached, which reason code stopped it). A self-contradicting answer is an error. No LLM judge
  yet; a qualitative check ("did Penny stay in character") would be the first place for one.
- Stub mode (`LLM_MODE=stub`) is a scripted teller and guardrail: fast, free and exact, so the
  full matrix runs in CI on every change against a baseline for the `evals` seed. Any change
  fails until the baseline is rewritten in the same commit; a Level 5 change that lets more
  through is reported as a guardrail regression.
- Live mode goes through the Message Batches API in rounds (a memo proxy answers the agent;
  `services/evals/README.md`). Nightly: a 10% sample per level. Full matrix: on changes to
  `content/` (prompts included), the policy engine or the agent. Opt-in, because it spends tokens.
- Every winning decision becomes a pin (`services/evals/pins.json`) that `PinnedWinsIT` replays
  on every build.
