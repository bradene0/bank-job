-- M2: attempts run end to end (Penny, MCP tools, policy engine, response cache).

-- A session plays one day's rotation for its whole life (security questions, Penny's wary
-- persona, the hidden note), even if it spans midnight UTC. The cache key uses it too.
ALTER TABLE sessions ADD COLUMN daily_seed text;
UPDATE sessions SET daily_seed = to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD');
ALTER TABLE sessions ALTER COLUMN daily_seed SET NOT NULL;

-- The customer's "memory aid" for their answer, returned by lookup_account. It spells the
-- answer out: the deliberate Level 3 leak (README, Levels).
ALTER TABLE customers ADD COLUMN security_answer_hint text NOT NULL DEFAULT '';
ALTER TABLE customers ALTER COLUMN security_answer_hint DROP DEFAULT;

ALTER TABLE attempts
    ADD COLUMN reply         text,
    ADD COLUMN stop_reason   text,
    ADD COLUMN input_tokens  integer NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    ADD COLUMN output_tokens integer NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    ADD COLUMN finished_at   timestamptz,
    ADD CONSTRAINT attempts_finished_with_outcome CHECK ((outcome IS NULL) = (finished_at IS NULL));

-- Every tool call Penny makes, in order, with the policy decision that allowed or denied it
-- and what the tool returned. `review` holds the Level 5 guardrail verdict, if one was asked.
ALTER TABLE tool_calls
    ADD COLUMN ordinal smallint NOT NULL CHECK (ordinal >= 1),
    ADD COLUMN result  jsonb    NOT NULL,
    ADD COLUMN review  jsonb,
    ADD CONSTRAINT tool_calls_tool_known CHECK (tool IN (
        'lookup_account', 'get_balance', 'verify_identity', 'read_document',
        'update_contact_info', 'transfer_funds', 'flag_for_review')),
    ADD CONSTRAINT tool_calls_attempt_ordinal_key UNIQUE (attempt_id, ordinal);

-- The cache stores what the model *decided* (its tool calls and reply), never what the bank
-- allowed: a cache hit replays the calls through the policy engine and the ledger of the
-- current session (ADR-0012). `variant` is the model plus a fingerprint of the content and
-- prompts, so a prompt edit or a model change can never serve stale decisions. The table
-- was unused before this migration.
DROP TABLE attempt_cache;
CREATE TABLE attempt_cache (
    level      smallint    NOT NULL CHECK (level BETWEEN 1 AND 5),
    daily_seed text        NOT NULL,
    combo_id   text        NOT NULL CHECK (combo_id ~ '^p[1-6]\.x[1-6]\.t[1-6]\.y[1-4]$'),
    variant    text        NOT NULL,
    transcript jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (level, daily_seed, combo_id, variant)
);

-- Haiku-class attempts cost a small fraction of a cent, so cents would round every attempt to
-- zero. Track micro-dollars instead (unused before this migration).
ALTER TABLE llm_usage RENAME COLUMN est_cost_cents TO est_cost_micros;
