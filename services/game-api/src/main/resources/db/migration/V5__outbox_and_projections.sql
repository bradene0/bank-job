-- M3: the outbox is written in the same transaction as the change it describes, then relayed
-- to Kafka (ADR-0002). Consumers keep projections that are rebuildable from the events, so they
-- carry no foreign keys into the tables the events came from.

-- Where each row goes. The table has been empty since V3, so NOT NULL needs no backfill.
ALTER TABLE outbox_events
    ADD COLUMN topic       text NOT NULL CHECK (topic IN (
        'bankjob.attempts', 'bankjob.tool-calls', 'bankjob.ledger', 'bankjob.outcomes')),
    ADD COLUMN message_key text NOT NULL;
-- The Level 5 staleness fence looks up ledger events for one account by key.
CREATE INDEX outbox_events_topic_key_idx ON outbox_events (topic, message_key);

-- Anomaly scoring (fed by bankjob.ledger): teller credits per destination account. The Level 5
-- velocity rule reads last_teller_credit_at. Updates are commutative (sum, count, max), so the
-- projection is correct whatever order events arrive in.
CREATE TABLE account_signals (
    account_id            uuid        PRIMARY KEY,
    session_id            uuid        NOT NULL,
    teller_credits        integer     NOT NULL CHECK (teller_credits >= 0),
    teller_credit_cents   bigint      NOT NULL CHECK (teller_credit_cents >= 0),
    last_teller_credit_at timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL DEFAULT now()
);

-- Leaderboard projection (fed by bankjob.outcomes). A session gets a row on its first finished
-- attempt; best_level stays NULL until it wins one, and only rows with a win are ranked.
ALTER TABLE leaderboard
    ADD COLUMN attempts_used integer NOT NULL DEFAULT 0 CHECK (attempts_used >= 0),
    ADD COLUMN stolen_cents  bigint  NOT NULL DEFAULT 0 CHECK (stolen_cents >= 0),
    ALTER COLUMN best_level DROP NOT NULL;
DROP INDEX leaderboard_score_idx;
CREATE INDEX leaderboard_ranked_idx ON leaderboard (score DESC) WHERE best_level IS NOT NULL;

-- Audit trail (fed by every topic). processed_events already makes the consumer idempotent;
-- the unique event id makes a duplicate impossible even if that check were bypassed.
ALTER TABLE audit_log ADD COLUMN event_id uuid UNIQUE;
