# evals (Python 3.14)

The attack matrix: every combo of tiles (6 × 6 × 6 × 4 = 864) on every level, played against a
running stack through the public API, the way the browser plays (tile ids only, a
`traceparent` per attempt). Grading is deterministic and comes from the API's answer: did money
reach the mule, the furthest tool Penny reached, and the policy reason code that stopped her. A
result that contradicts itself (a win with no posted transfer, a refusal that tried a transfer)
is an error, not a data point.

```
uv sync --locked
uv run ruff check . && uv run ruff format --check . && uv run mypy src tests
uv run pytest
```

## Running the matrix (stub mode)

Stub mode is free and exact: the scripted teller and guardrail give one answer per combo, so a
full run is repeatable and CI plays it on every change.

```
DAILY_SEED_OVERRIDE=evals SESSIONS_MAX_PER_IP_PER_HOUR=20000 docker compose up -d --build --wait
cd services/evals
uv run bankjob-evals run --daily-seed evals --baseline baselines/stub-evals.json
uv run bankjob-evals pin --check
```

A full run grades 6,048 combos (7,776 attempts with the Level 4 setups) in about 6 minutes on
an Apple M5, most of it reading each fresh attempt's trace for its replay file; with
`--no-replays` and a warm response cache it takes under 2 minutes.

| Output (`out/`) | |
|---|---|
| `matrix.json` | One row per played combo and setup: outcome, amount, furthest tool, stopping reason code, every denial, guardrail verdicts, trace id; the model's decisions for wins |
| `summary.json` | Per level and setup: outcome counts, win rate, stop reasons, win rate per tile, and a persona × technique heatmap (`won` / `total` grids) |
| `replay/{level}/{comboId}.json` | Replay files for the game (`docs/contracts/replay.schema.json`), validated before they are written |

## How a run uses sessions

Every session is a private copy of the branch (ADR-0009), and a session keeps its state across
levels (ADR-0016). So:

- **fresh**: each (level, combo) gets a brand-new session. Results do not depend on order or on
  other combos, the run can go wide (8 at a time), and the result is what a first-time player
  sees, which is what its replay file shows.
- **after_l4_phone_change** (Level 5): a new session first replays a Level 4 combo that put the
  caller's phone on the vault without winning, picked from the fresh Level 4 results. Level 5's
  step-up text then reaches the caller.
- **after_l4_win** (Level 5): the Level 4 combo also won. The harness then waits until anomaly
  scoring has consumed that transfer (the trace shows the `anomaly-scoring` consumer span)
  before the Level 5 attempt, so the velocity rule reads a settled signal: the result is
  `anomaly_velocity`, never a race with `anomaly_signals_stale` (ADR-0013).

## Regressions

- **Stub baseline** (`baselines/stub-evals.json`): every combo's outcome and stopping reason for
  the `evals` seed. Any difference fails CI until the baseline is rewritten in the same change
  (`uv run bankjob-evals baseline --write baselines/stub-evals.json`), so every shift shows up in
  review. A **guardrail regression** is reported on its own: a Level 5 combo that now wins, or a
  guardrail verdict that turned from deny to allow.
- **Pinned wins** (`pins.json`): every winning decision any run found (stub or live): the tool
  calls, arguments and guardrail verdicts, deduplicated per level and setup, with the combos
  that produced them. game-api's `PinnedWinsIT` replays each one through the current policy
  engine (via the response cache, ADR-0012) and checks its expected outcome. `pin --check`
  fails CI when a stub win is not pinned; after a live run, `pin` adds new wins and fails on a
  new Level 5 win. When a fix closes a hole on purpose, set that pin's `expected` to the new
  outcome and add a `note`: it then guards the fix.

## Live mode, through the Message Batches API

Penny's tool loop needs each answer before her next call, but a batch answers later. So a live
run goes in rounds (`batches.py`):

1. The agent runs live with its Messages API base URL pointed at a memo proxy in the harness. The
   proxy answers calls it has seen answered and defers the rest; game-api reports a deferred run
   as 503 `replay: true` and refunds the nerve.
2. Between rounds, every deferred call goes to Anthropic in one message batch (50% of the price).
   Answers are memoized by a hash of the request body (also the batch `custom_id`).
3. Deferred combos are replayed in fresh sessions. Memoized answers carry Penny one step further
   each round, through the real policy engine and ledger, until she finishes. That takes about
   as many rounds as she makes model calls (5 with the scripted fake).

Only the harness holds `ANTHROPIC_API_KEY`; the agent gets a placeholder and can only reach the
proxy. Before the matrix starts, one preflight attempt must go through the proxy, or the run
stops. Answers are kept in `out/memo/`, so a rerun or an interrupted run never pays twice.

```
docker compose -f docker-compose.yml -f infra/compose/evals-batches.yml up -d --build --wait
cd services/evals
ANTHROPIC_API_KEY=... uv run bankjob-evals run --daily-seed evals --batches [--sample 0.1]
uv run bankjob-evals pin
```

On Linux add `--proxy-bind 0.0.0.0` (containers reach the host on its bridge address). Tests
run the real SDK against `tests/fake_batches.py`, a fake Message Batches API with a scripted
Penny; CI also plays a sample through the whole live path with it. The scheduled workflow
(`.github/workflows/evals-live.yml`) runs a 10% sample nightly and the full matrix when content,
the policy engine or the agent changes; it is off until `EVALS_LIVE_ENABLED` is set.
