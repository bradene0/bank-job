# Kafka event contracts

One JSON Schema per event lives here (added in M3). Required events:

| Event | Topic | Key | Produced by |
|---|---|---|---|
| `AttemptSubmitted` | `bankjob.attempts` | session_id | game-api (outbox) |
| `ToolCalled` | `bankjob.tool-calls` | attempt_id | game-api (outbox) |
| `PolicyDecided` | `bankjob.tool-calls` | attempt_id | game-api (outbox) |
| `TransferPosted` | `bankjob.ledger` | account_id | game-api (outbox) |
| `AttemptResolved` | `bankjob.outcomes` | session_id | game-api (outbox) |

Every event has: `event_id` (UUID), `event_type`, `occurred_at`, `schema_version`, and
payload fields. Trace context (`traceparent`) travels in Kafka headers. Consumers are
idempotent via the `processed_events` table. Failed messages go to `<topic>.dlq` after
bounded retries with backoff.
