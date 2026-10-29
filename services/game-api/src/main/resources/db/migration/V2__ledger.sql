-- Double-entry ledger. All money is bigint cents. Invariants enforced here, not just in Java:
--   1. every journal entry's postings sum to zero          (deferred constraint trigger)
--   2. every journal entry has at least two postings        (deferred constraint trigger)
--   3. balances equal the sum of postings                   (service + reconciliation test)
--   4. only equity accounts may go negative                 (check constraint)
--   5. journal entries and postings are append-only         (triggers)

CREATE TABLE customers (
    id                   uuid        PRIMARY KEY DEFAULT uuidv7(),
    session_id           uuid        NOT NULL REFERENCES sessions (id),
    display_name         text        NOT NULL,
    security_question    text        NOT NULL,
    security_answer_hash text        NOT NULL,
    phone                text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX customers_session_id_idx ON customers (session_id);

CREATE TABLE accounts (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    session_id  uuid        NOT NULL REFERENCES sessions (id),
    customer_id uuid        REFERENCES customers (id),  -- NULL for the mule and equity accounts
    number      text        NOT NULL,                   -- e.g. BV-MULE-01, unique per session
    -- 'equity' is the per-session opening-balance account that funds the branch.
    kind        text        NOT NULL CHECK (kind IN ('checking', 'vault', 'mule', 'equity')),
    currency    text        NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, number),
    UNIQUE (id, kind)  -- target for account_balances' composite FK below
);

-- One row per account, updated in the same transaction as its postings. `version` is the
-- optimistic lock. `kind` is copied from accounts (and pinned by the composite FK) so the
-- non-negative rule can be a plain CHECK constraint.
CREATE TABLE account_balances (
    account_id    uuid   PRIMARY KEY,
    kind          text   NOT NULL,
    balance_cents bigint NOT NULL DEFAULT 0,
    version       bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (account_id, kind) REFERENCES accounts (id, kind),
    CONSTRAINT account_balances_non_negative CHECK (kind = 'equity' OR balance_cents >= 0)
);

CREATE TABLE journal_entries (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    attempt_id         uuid,
    attempt_created_at timestamptz,
    description        text        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (attempt_id, attempt_created_at) REFERENCES attempts (id, created_at),
    CHECK ((attempt_id IS NULL) = (attempt_created_at IS NULL))
);

CREATE TABLE postings (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    entry_id     uuid        NOT NULL REFERENCES journal_entries (id),
    account_id   uuid        NOT NULL REFERENCES accounts (id),
    amount_cents bigint      NOT NULL CHECK (amount_cents <> 0),  -- debit < 0, credit > 0
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX postings_account_id_idx ON postings (account_id);
CREATE INDEX postings_entry_id_idx ON postings (entry_id);

-- Invariants 1 and 2, checked at COMMIT so an entry and its postings can be inserted in any
-- order inside one transaction. Fires for new entries (catches an entry with no postings)
-- and for new postings (catches an unbalanced set).
CREATE FUNCTION check_journal_entry_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    target_entry uuid;
    posting_count integer;
    total numeric;
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        target_entry := NEW.id;
    ELSE
        target_entry := NEW.entry_id;
    END IF;

    SELECT count(*), coalesce(sum(amount_cents), 0)
      INTO posting_count, total
      FROM postings
     WHERE entry_id = target_entry;

    IF posting_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least 2 are required',
            target_entry, posting_count USING ERRCODE = 'check_violation';
    END IF;
    IF total <> 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: postings sum to % cents',
            target_entry, total USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();

-- Invariant 5: corrections are new compensating entries, never edits.
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER postings_no_truncate
    BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();
