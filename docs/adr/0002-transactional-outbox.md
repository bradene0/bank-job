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
