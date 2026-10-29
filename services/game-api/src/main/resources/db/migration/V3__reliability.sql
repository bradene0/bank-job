-- Idempotency for transfer_funds: a retried request with the same key returns the stored
-- response instead of moving money twice. The row is written in the same transaction as
-- the postings, so a key exists only if its transfer committed.
CREATE TABLE idempotency_keys (
    key           text        PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 200),
    request_hash  text        NOT NULL,  -- same key + different request = conflict
    response_json jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Transactional outbox (ADR-0002); publisher and consumers arrive in M3.
CREATE TABLE outbox_events (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    trace_context  jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);
CREATE INDEX outbox_events_unpublished_idx
    ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
