# Data model (PostgreSQL)

Starting design. It may be refined, but must keep the invariants and record
significant changes in an ADR. All money is `bigint` cents; never floating point.

## Ledger

```
customers(id, display_name, security_question, security_answer_hash, phone, created_at)
accounts(id, customer_id → customers, kind ['checking','vault','mule'], currency,
         version bigint,            -- optimistic locking
         created_at)
journal_entries(id, attempt_id → attempts NULL, description, created_at)
postings(id, entry_id → journal_entries, account_id → accounts,
         amount_cents bigint,       -- signed: debit negative, credit positive
         created_at)
account_balances(account_id PK → accounts, balance_cents bigint, version bigint)
                                    -- maintained in the same transaction as postings
```

**Invariants (enforced in the database, tested with jqwik):**
1. Every journal entry's postings sum to zero. Enforce with a `CONSTRAINT TRIGGER ...
   DEFERRABLE INITIALLY DEFERRED` that checks the sum at commit.
2. Every entry has at least two postings.
3. `account_balances.balance_cents` equals the sum of that account's postings
   (verified by a reconciliation job and a test).
4. A balance may not go below zero for `checking` accounts (check constraint).

## Reliability tables

```
idempotency_keys(key PK, request_hash, response_json, created_at)   -- for transfer_funds
outbox_events(id, aggregate_type, aggregate_id, event_type, payload jsonb,
              trace_context jsonb, created_at, published_at NULL)  -- index on unpublished
processed_events(consumer, event_id, processed_at, PK(consumer, event_id))  -- idempotent consumers
```

## Game tables

```
sessions(id, created_at, ip_hash, nerve_remaining jsonb)
attempts(id, session_id → sessions, level, combo_id, tiles jsonb, daily_seed,
         outcome ['won','blocked','refused','error'], amount_cents, cached bool,
         trace_id, created_at)
         -- PARTITION BY RANGE (created_at), monthly partitions
tool_calls(id, attempt_id, tool, args jsonb, policy_decision ['allow','deny'],
           policy_reason, latency_ms, created_at)
leaderboard(session_id, display_alias, score, best_level, updated_at)  -- Kafka projection
audit_log(id, actor, action, target, details jsonb, created_at)
llm_usage(day, input_tokens, output_tokens, est_cost_cents)  -- budget switch reads this
attempt_cache(level, daily_seed, combo_id, result jsonb, PK(level, daily_seed, combo_id))
```

## Notes

- Indexes: `postings(account_id)`, `postings(entry_id)`, `outbox_events(published_at)
  WHERE published_at IS NULL`, `attempts(session_id)`, `tool_calls(attempt_id)`.
- IP addresses are stored only as salted hashes.
- Retention: partitions older than 90 days are dropped by a scheduled job.
