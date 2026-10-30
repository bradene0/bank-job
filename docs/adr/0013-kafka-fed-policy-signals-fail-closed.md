# ADR-0013: Level 5 anomaly signals come from Kafka and fail closed when stale

**Status:** Accepted (2026-10-29)

## Context
Level 5's velocity rule ("no teller transfer into the same account in the last 15
minutes") is meant to be fed by Kafka: an anomaly-scoring consumer reads `TransferPosted`
events and keeps per-account signals, and the policy engine reads those signals. Until
now the rule queried the ledger directly.

A projection fed through the outbox and Kafka is eventually consistent. It lags the ledger
by the outbox poll, the broker round trip and the consumer, normally milliseconds, but
without limit while Kafka is down. A security control that reads a lagging signal as
"nothing happened" has a hole exactly when the system is degraded.

Options:
1. Keep reading the ledger synchronously and use Kafka only for reporting.
2. Read the projection and accept the lag.
3. Read the projection, and deny while it may be behind for the account in question.

## Decision
Option 3. `AnomalySignals.credits(account)` returns the projection (`account_signals`)
together with a staleness flag: true if any ledger event keyed to that account is still in
`outbox_events` without a `processed_events` claim by the `anomaly-scoring` consumer. The
policy engine denies with `anomaly_signals_stale` while the flag is set, and otherwise
applies the velocity rule to the projection.

The staleness check is one indexed query on the same database (`outbox_events` by topic and
key). It is exact because the outbox row and the consumer's claim are both written
transactionally: a row without a claim is an event the projection has not applied.

The repeat-transfer rule (one transfer per attempt) stays synchronous: it is about the
current attempt, whose facts the policy engine already has.

## Consequences
- The rule is only ever as permissive as the ledger. While Kafka is down, Level 5
  transfers into an account with recent activity are denied rather than waved through.
  Transfers into an account with no pending events are unaffected.
- Normal lag is invisible to players: attempts are seconds apart and the scorer finishes
  within milliseconds (measured outbox lag in the low tens of milliseconds locally).
- Tests and the eval harness must wait for the scorer (or expect `anomaly_signals_stale`)
  before a second Level 5 transfer into the same account.
- Outbox rows are deleted a day after publication. That is far longer than the 15-minute
  window, so the fence never depends on rows the janitor removed.
