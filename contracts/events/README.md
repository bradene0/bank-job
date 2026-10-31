# Kafka event contracts

One JSON Schema (2020-12) per event. `EventContractIT` validates every event game-api
actually publishes against these files, so the schemas cannot drift from the code.

| Event | Topic | Key | Written by (same transaction as) |
|---|---|---|---|
| `AttemptSubmitted` | `bankjob.attempts` | session_id | the attempt row |
| `ToolCalled` | `bankjob.tool-calls` | attempt_id | the `tool_calls` row |
| `PolicyDecided` | `bankjob.tool-calls` | attempt_id | the `tool_calls` row |
| `TransferPosted` | `bankjob.ledger` | to_account_id | the journal entry and postings |
| `AttemptResolved` | `bankjob.outcomes` | session_id | the attempt's outcome |

Every event has `event_id` (UUID, the outbox row id), `event_type`, `schema_version` (1)
and `occurred_at`, then its payload fields, all in snake_case. Unknown fields are not
allowed; a breaking change bumps `schema_version`, and consumers reject versions they do
not know (the message goes to the DLQ).

Headers: `traceparent` (W3C trace context, added by the producer's tracing), `event_id`
and `event_type` (so a DLQ message can be inspected without parsing it).

## Delivery

- **Producer:** the transactional outbox (ADR-0002). `OutboxRelay` sends committed rows
  with an idempotent producer (`acks=all`) and marks them published. Delivery is at least
  once: a crash between the ack and the mark sends a row again.
- **Consumers:** each one claims `(consumer, event_id)` in `processed_events` in the same
  transaction as its update, so a redelivered event is skipped.

| Consumer group | Topics | Maintains |
|---|---|---|
| `anomaly-scoring` | `bankjob.ledger` | `account_signals` (Level 5 velocity rule) |
| `leaderboard` | `bankjob.outcomes` | `leaderboard` (`GET /v1/leaderboard`) |
| `audit` | all four | `audit_log` |

- **Failures:** a transient error is retried with exponential backoff (3 retries); then,
  or at once for a malformed message (not JSON, unknown type or version, missing field),
  the record goes to `<topic>.dlq` with the exception in its headers, and the partition
  moves on.
- **Topics:** 3 partitions each (1 for DLQs), replication factor 1 (single broker). The
  broker never auto-creates topics; the outbox relay creates them before its first send,
  so game-api's startup never waits for Kafka.
