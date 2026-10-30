# ADR-0002: Transactional outbox for Kafka publishing

**Status:** Accepted (2026-10-29)

## Context
Ledger writes and Kafka events must not diverge. Writing to Postgres and then Kafka in two
steps (dual write) can lose or duplicate events when either side fails.

## Decision
Write events to an `outbox_events` table in the same transaction as the ledger change. A
publisher sends unpublished rows to Kafka and marks them published. Consumers are idempotent
using a `processed_events` table.

## Consequences
- At-least-once delivery with idempotent consumers gives effectively-once processing.
- Slight publish delay (outbox lag) that is measured as a metric.
- A failure-injection test (stop Kafka mid-transfer) proves recovery.

## Implementation (2026-10-29)
- **Writers.** `Outbox.append` requires an open transaction (`Propagation.MANDATORY`), so
  an event cannot be written outside the change it describes. The ledger appends
  `TransferPosted` with the postings; the attempt store appends `AttemptSubmitted`,
  `ToolCalled` + `PolicyDecided` and `AttemptResolved` with their rows. Each row stores the
  writer's W3C trace context, so the event continues the request's trace when it is sent.
- **Relay.** One background thread selects `published_at IS NULL` rows in creation order
  (`FOR UPDATE SKIP LOCKED`, batches of 100), sends them with an idempotent producer
  (`acks=all`), and marks the acked ones published. A commit wakes it; otherwise it polls
  every second, backing off to 15 s while the broker is down. Selecting by "unpublished"
  rather than by position means a transaction that commits late is never skipped.
- **Topics** are created by the relay before its first send, not at startup, so game-api
  never waits for the broker after a wake. The broker does not auto-create topics.
- **Consumers** claim `(consumer, event_id)` in `processed_events` in the same transaction
  as their update. Malformed messages go straight to `<topic>.dlq`; other failures are
  retried three times with backoff first.
- **Retention.** Published rows are deleted after a day (`OutboxJanitor`).
- **Proof.** `OutboxKafkaOutageIT` pauses the broker while attempts move money, then
  resumes it and re-sends every event as if the relay had crashed before marking them; the
  projections must match the ledger exactly. CI also stops the compose broker mid-game.
- Outbox lag is recorded per event on the relay span (`outbox.lag_ms`) rather than as a
  metric, because only traces are exported so far.
