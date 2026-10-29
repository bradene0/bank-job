-- Game tables. Every player session gets its own sandboxed branch (customers, accounts,
-- ledger rows); see ADR-0009.

CREATE TABLE sessions (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    ip_hash         text        NOT NULL,  -- HMAC of the client IP; raw IPs are never stored
    nerve_remaining jsonb       NOT NULL,  -- {"1": 8, "2": 7, ...} attempts left per level
    CHECK (expires_at > created_at)
);
CREATE INDEX sessions_created_at_idx ON sessions (created_at);

-- Monthly range partitions so retention can drop whole months cheaply. A partitioned
-- table's primary key must include the partition key, hence (id, created_at); tables
-- that reference an attempt carry both columns (ADR-0010).
CREATE TABLE attempts (
    id           uuid        NOT NULL DEFAULT uuidv7(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    session_id   uuid        NOT NULL REFERENCES sessions (id),
    level        smallint    NOT NULL CHECK (level BETWEEN 1 AND 5),
    combo_id     text        NOT NULL CHECK (combo_id ~ '^p[1-6]\.x[1-6]\.t[1-6]\.y[1-4]$'),
    tiles        jsonb       NOT NULL,
    daily_seed   text        NOT NULL,
    -- NULL while the attempt is in flight; set once Penny's run finishes.
    outcome      text        CHECK (outcome IN ('won', 'blocked', 'refused', 'error')),
    amount_cents bigint      NOT NULL DEFAULT 0 CHECK (amount_cents >= 0),
    cached       boolean     NOT NULL DEFAULT false,
    trace_id     text,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX attempts_session_id_idx ON attempts (session_id);

-- Creates the partition covering the UTC month that contains `day`. Idempotent; called at
-- migration time and by the partition maintenance job in game-api.
CREATE FUNCTION ensure_attempts_partition(day date) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    month_start date := date_trunc('month', day)::date;
    month_end   date := (date_trunc('month', day) + interval '1 month')::date;
    part_name   text := format('attempts_%s', to_char(month_start, 'YYYY_MM'));
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF attempts FOR VALUES FROM (%L) TO (%L)',
        part_name, month_start::text || ' 00:00:00+00', month_end::text || ' 00:00:00+00');
    RETURN part_name;
END;
$$;

SELECT ensure_attempts_partition(((now() AT TIME ZONE 'UTC') + make_interval(months => m))::date)
FROM generate_series(0, 2) AS m;

CREATE TABLE tool_calls (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    attempt_id         uuid        NOT NULL,
    attempt_created_at timestamptz NOT NULL,
    tool               text        NOT NULL,
    args               jsonb       NOT NULL,
    policy_decision    text        NOT NULL CHECK (policy_decision IN ('allow', 'deny')),
    policy_reason      text        NOT NULL,
    latency_ms         integer     NOT NULL CHECK (latency_ms >= 0),
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (attempt_id, attempt_created_at) REFERENCES attempts (id, created_at)
);
CREATE INDEX tool_calls_attempt_id_idx ON tool_calls (attempt_id);

CREATE TABLE leaderboard (
    session_id    uuid        PRIMARY KEY REFERENCES sessions (id),
    display_alias text        NOT NULL,
    score         bigint      NOT NULL,
    best_level    smallint    NOT NULL CHECK (best_level BETWEEN 1 AND 5),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX leaderboard_score_idx ON leaderboard (score DESC);

CREATE TABLE audit_log (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    actor      text        NOT NULL,
    action     text        NOT NULL,
    target     text        NOT NULL,
    details    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE llm_usage (
    day            date   PRIMARY KEY,
    input_tokens   bigint NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens  bigint NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    est_cost_cents bigint NOT NULL DEFAULT 0 CHECK (est_cost_cents >= 0)
);

CREATE TABLE attempt_cache (
    level      smallint    NOT NULL CHECK (level BETWEEN 1 AND 5),
    daily_seed text        NOT NULL,
    combo_id   text        NOT NULL,
    result     jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (level, daily_seed, combo_id)
);
