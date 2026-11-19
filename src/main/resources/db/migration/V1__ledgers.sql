-- =====================================================================
-- ACTION LEDGER: the source of truth. Append-only, hash-chained.
-- Everything else in this schema is a projection rebuilt from it.
-- =====================================================================
CREATE TABLE action_event (
    seq          BIGINT      PRIMARY KEY,            -- gapless, assigned under a lock
    event_id     UUID        NOT NULL UNIQUE,
    tx_id        TEXT        NOT NULL UNIQUE,        -- idempotency key from the caller
    type         TEXT        NOT NULL,
    token_code   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    request_hash TEXT        NOT NULL,               -- detects a reused tx_id with a different request
    occurred_at  TIMESTAMPTZ NOT NULL,
    prev_hash    TEXT        NOT NULL,
    hash         TEXT        NOT NULL UNIQUE
);

CREATE INDEX action_event_token_idx ON action_event (token_code, seq);

-- History is immutable: no UPDATE, DELETE or TRUNCATE, ever.
CREATE FUNCTION action_event_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'action_event is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER action_event_no_update_delete
    BEFORE UPDATE OR DELETE ON action_event
    FOR EACH ROW EXECUTE FUNCTION action_event_immutable();

CREATE TRIGGER action_event_no_truncate
    BEFORE TRUNCATE ON action_event
    FOR EACH STATEMENT EXECUTE FUNCTION action_event_immutable();

-- =====================================================================
-- TOKEN LEDGER: double-entry postings and the balances they add up to.
-- Projection: may be dropped and rebuilt from action_event at any time.
-- =====================================================================
CREATE TABLE posting (
    id          BIGSERIAL PRIMARY KEY,
    event_seq   BIGINT NOT NULL REFERENCES action_event (seq),
    tx_id       TEXT   NOT NULL,
    token_code  TEXT   NOT NULL,
    account     TEXT   NOT NULL,
    amount      BIGINT NOT NULL CHECK (amount <> 0)
);

CREATE INDEX posting_account_idx ON posting (account, token_code, event_seq);
CREATE INDEX posting_event_idx ON posting (event_seq);

-- Cached sum of postings per account. Only the projector writes it.
-- Only the mint account may go negative (it is where supply comes from).
CREATE TABLE balance (
    account     TEXT   NOT NULL,
    token_code  TEXT   NOT NULL,
    amount      BIGINT NOT NULL,
    PRIMARY KEY (account, token_code),
    CONSTRAINT balance_not_overdrawn CHECK (amount >= 0 OR account = '@mint')
);

-- Token definitions and their workflow rules (from TOKEN_CREATED events).
CREATE TABLE token (
    code                TEXT        PRIMARY KEY,
    name                TEXT        NOT NULL,
    kind                TEXT        NOT NULL,
    kes_per_token       BIGINT,
    reservation_minutes INT         NOT NULL,
    max_supply          BIGINT,
    created_seq         BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL
);

-- Reservations (from RESERVED / PURCHASED / RELEASED / EXPIRED events).
CREATE TABLE reservation (
    id           UUID        PRIMARY KEY,
    token_code   TEXT        NOT NULL,
    seller       TEXT        NOT NULL,
    buyer        TEXT        NOT NULL,
    amount       BIGINT      NOT NULL,
    status       TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    reserved_seq BIGINT      NOT NULL,
    closed_seq   BIGINT
);

CREATE INDEX reservation_open_idx ON reservation (status, expires_at);
