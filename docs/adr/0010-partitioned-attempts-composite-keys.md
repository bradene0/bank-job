# ADR-0010: Partitioned attempts table with composite keys

**Status:** Accepted (2026-10-29)

## Context
`attempts` is range-partitioned by month on `created_at` so retention can drop whole
months cheaply. PostgreSQL requires a partitioned table's primary key, and any unique key a
foreign key points at, to include the partition key. A plain `REFERENCES attempts (id)`
from `tool_calls` or `journal_entries` is therefore impossible.

Options:
1. Composite key `(id, created_at)`, with referencing tables carrying both columns.
2. No foreign keys into `attempts`; enforce integrity in the application.
3. Don't partition.

## Decision
Option 1. `attempts` has `PRIMARY KEY (id, created_at)`. `tool_calls` and
`journal_entries` store `attempt_id` and `attempt_created_at` and reference both columns;
on `journal_entries` both are nullable (opening balances have no attempt), and a CHECK
requires both or neither. In Java, an `AttemptRef(id, createdAt)` record keeps the pair
together.

Partitions are created ahead of time: the migration creates the current month and the next
two, and a job in game-api calls `ensure_attempts_partition()` at startup and daily. There is
no default partition, so a missing month fails loudly instead of collecting rows that
would block attaching that month's partition later.

## Consequences
- Referential integrity stays in the database, consistent with the ledger invariants.
- One extra timestamp column on referencing rows.
- Dropping an old partition requires deleting (or archiving) the rows that reference it
  first. The retention job must handle that ordering.
