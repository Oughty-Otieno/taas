-- =====================================================================
-- V2: the platform schema. Replaces the MVP tables (postings + a stored
-- balance) with signed movements and a derived holding projection.
-- The MVP history is dropped: nothing in it was production data.
-- =====================================================================
DROP TABLE IF EXISTS posting, balance, reservation, token, action_event CASCADE;
DROP FUNCTION IF EXISTS action_event_immutable() CASCADE;

-- Rows in these tables may be inserted, never changed.
CREATE FUNCTION refuse_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% rejected)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

-- =====================================================================
-- ACTION LEDGER: why ownership changed. Append-only and hash-chained:
-- hash = sha256(prev_hash || canonical(event)). In production the app
-- role is granted INSERT and SELECT on this table and nothing else.
-- =====================================================================
CREATE TABLE action_event (
    id              UUID        PRIMARY KEY,
    seq             BIGINT      NOT NULL UNIQUE,   -- gapless, assigned under the chain lock
    event_type      TEXT        NOT NULL,
    subject_id      TEXT        NOT NULL,          -- token, rule set or account
    token_id        TEXT,                          -- the token involved, when there is one
    actor_id        TEXT        NOT NULL,          -- a client, a service or a timer
    idempotency_key TEXT        NOT NULL,
    rule_version    TEXT        NOT NULL,          -- which rule set / policy version decided this
    payload         JSONB       NOT NULL,
    prev_hash       BYTEA       NOT NULL,
    hash            BYTEA       NOT NULL UNIQUE,
    occurred_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX action_event_token_idx ON action_event (token_id, seq);
CREATE INDEX action_event_type_idx ON action_event (event_type, seq);
CREATE INDEX action_event_actor_idx ON action_event (actor_id, seq);
CREATE INDEX action_event_time_idx ON action_event (occurred_at);

CREATE TRIGGER action_event_no_update_delete BEFORE UPDATE OR DELETE ON action_event
    FOR EACH ROW EXECUTE FUNCTION refuse_change();
CREATE TRIGGER action_event_no_truncate BEFORE TRUNCATE ON action_event
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_change();

-- =====================================================================
-- Token definitions (projection of TOKEN_DEFINED). The ownership cap and
-- the authorised supply live here so the database can enforce them.
-- =====================================================================
CREATE TABLE token (
    token_id            TEXT        PRIMARY KEY,
    name                TEXT        NOT NULL,
    kind                TEXT        NOT NULL CHECK (kind IN ('LOYALTY', 'ASSET')),
    asset_type          TEXT,
    backing             TEXT,
    valuation_kes       BIGINT,
    unit_price_kes      BIGINT,
    redeem_value_kes    BIGINT,
    authorised_supply   BIGINT      NOT NULL CHECK (authorised_supply > 0),
    max_holder_bps      INT         CHECK (max_holder_bps BETWEEN 1 AND 10000),
    reservation_minutes INT         NOT NULL,
    kyc_required        BOOLEAN     NOT NULL,
    inactivity_months   INT,
    rule_set_id         TEXT,
    policy_version      TEXT        NOT NULL,
    defined_seq         BIGINT      NOT NULL,
    defined_at          TIMESTAMPTZ NOT NULL
);

-- =====================================================================
-- TOKEN LEDGER: ownership is a sum of signed movements. Each action's
-- movements sum to zero. No row is updated or deleted; a mistake is
-- corrected by a compensating movement. (Replay truncates and rebuilds
-- this table from the Action Ledger: it is derived.)
-- =====================================================================
CREATE TABLE token_movement (
    id           BIGSERIAL   PRIMARY KEY,
    token_id     TEXT        NOT NULL REFERENCES token (token_id),
    account_id   TEXT        NOT NULL,                -- customer, @treasury, @mint, @burn
    bucket       TEXT        NOT NULL CHECK (bucket IN ('AVAILABLE', 'RESERVED')),
    units        BIGINT      NOT NULL CHECK (units <> 0),   -- signed integer, never a float
    action_id    UUID        NOT NULL REFERENCES action_event (id),
    rule_version TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX token_movement_account_idx ON token_movement (account_id, token_id, id);
CREATE INDEX token_movement_action_idx ON token_movement (action_id);

CREATE TRIGGER token_movement_no_update_delete BEFORE UPDATE OR DELETE ON token_movement
    FOR EACH ROW EXECUTE FUNCTION refuse_change();

-- =====================================================================
-- Derived holdings: an incremental projection of token_movement kept by
-- the trigger below, in the same transaction, by atomic increments. No
-- application code writes it, and no write path reads it to decide.
--
-- The invariants are CHECK constraints on this derived row:
--   * no overdraft            available, reserved >= 0 (except @mint)
--   * supply ceiling          -@mint.available <= authorised supply
--   * 20% ownership cap       available + reserved <= cap
-- A concurrent request cannot slip past them: the row is locked by the
-- increment and the check runs on the value that actually commits.
-- =====================================================================
CREATE TABLE holding (
    token_id     TEXT   NOT NULL REFERENCES token (token_id),
    account_id   TEXT   NOT NULL,
    available    BIGINT NOT NULL DEFAULT 0,
    reserved     BIGINT NOT NULL DEFAULT 0,
    max_units    BIGINT,          -- ownership cap, holders of capped tokens only
    supply_floor BIGINT,          -- -authorised supply, @mint only
    PRIMARY KEY (token_id, account_id),
    CONSTRAINT holding_no_overdraft CHECK (supply_floor IS NOT NULL OR available >= 0),
    CONSTRAINT holding_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT holding_supply_ceiling CHECK (supply_floor IS NULL OR available >= supply_floor),
    CONSTRAINT holding_ownership_cap CHECK (max_units IS NULL OR available + reserved <= max_units)
);

-- New holding rows take their limits from the token definition.
CREATE FUNCTION holding_limits() RETURNS trigger AS $$
DECLARE
    t token%ROWTYPE;
BEGIN
    SELECT * INTO t FROM token WHERE token_id = NEW.token_id;
    IF NEW.account_id = '@mint' THEN
        NEW.supply_floor := -t.authorised_supply;
    ELSIF left(NEW.account_id, 1) <> '@' AND t.max_holder_bps IS NOT NULL THEN
        NEW.max_units := (t.authorised_supply * t.max_holder_bps) / 10000;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER holding_set_limits BEFORE INSERT ON holding
    FOR EACH ROW EXECUTE FUNCTION holding_limits();

CREATE FUNCTION apply_movement() RETURNS trigger AS $$
BEGIN
    -- Update first and insert only for a new holder: INSERT ... ON CONFLICT would check the
    -- constraints against the proposed row, refusing a debit of an existing holding.
    IF NEW.bucket = 'AVAILABLE' THEN
        UPDATE holding SET available = available + NEW.units
         WHERE token_id = NEW.token_id AND account_id = NEW.account_id;
    ELSE
        UPDATE holding SET reserved = reserved + NEW.units
         WHERE token_id = NEW.token_id AND account_id = NEW.account_id;
    END IF;
    IF NOT FOUND THEN
        INSERT INTO holding (token_id, account_id, available, reserved)
        VALUES (NEW.token_id, NEW.account_id,
                CASE WHEN NEW.bucket = 'AVAILABLE' THEN NEW.units ELSE 0 END,
                CASE WHEN NEW.bucket = 'RESERVED' THEN NEW.units ELSE 0 END);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER token_movement_apply AFTER INSERT ON token_movement
    FOR EACH ROW EXECUTE FUNCTION apply_movement();

-- =====================================================================
-- Workflow projections, all rebuilt from the Action Ledger by replay.
-- =====================================================================
CREATE TABLE reservation (
    reservation_id UUID        PRIMARY KEY,
    token_id       TEXT        NOT NULL,
    account_id     TEXT        NOT NULL,
    units          BIGINT      NOT NULL,
    amount_kes     BIGINT      NOT NULL,
    status         TEXT        NOT NULL,   -- HELD | PURCHASED | RELEASED | EXPIRED | PAYMENT_FAILED
    expires_at     TIMESTAMPTZ NOT NULL,
    reserved_seq   BIGINT      NOT NULL,
    closed_seq     BIGINT
);

CREATE INDEX reservation_open_idx ON reservation (status, expires_at);

CREATE TABLE purchase_order (
    order_id       UUID        PRIMARY KEY,
    reservation_id UUID        NOT NULL UNIQUE,
    token_id       TEXT        NOT NULL,
    account_id     TEXT        NOT NULL,
    units          BIGINT      NOT NULL,
    amount_kes     BIGINT      NOT NULL,
    payment_method TEXT        NOT NULL,
    status         TEXT        NOT NULL,   -- AWAITING_PAYMENT | PAID | PAYMENT_FAILED | EXPIRED | REFUND_REQUIRED
    psp_reference  TEXT,
    placed_seq     BIGINT      NOT NULL,
    updated_seq    BIGINT      NOT NULL
);

CREATE TABLE rule_set_version (
    rule_set_id    TEXT        NOT NULL,
    version        TEXT        NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    document       JSONB       NOT NULL,
    published_by   TEXT        NOT NULL,
    published_at   TIMESTAMPTZ NOT NULL,
    published_seq  BIGINT      NOT NULL,
    PRIMARY KEY (rule_set_id, version)
);

-- =====================================================================
-- Not derived from the ledger.
-- =====================================================================

-- "Same key, same reply": the stored response for every idempotency key,
-- written in the same transaction as the events it answers for.
CREATE TABLE idempotency_record (
    client_id    TEXT        NOT NULL,
    idem_key     TEXT        NOT NULL,
    operation    TEXT        NOT NULL,
    request_hash TEXT        NOT NULL,
    status_code  INT         NOT NULL,
    response     JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_id, idem_key)
);

-- Transactional outbox: written with the events, published after commit.
CREATE TABLE outbox (
    id           BIGSERIAL   PRIMARY KEY,
    action_id    UUID        NOT NULL REFERENCES action_event (id),
    event_type   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

-- Customer profiles the policy decision point reads (KYC). Owned by an
-- onboarding system in a real deployment; seeded here for the demo.
CREATE TABLE account_profile (
    account_id   TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    kyc_status   TEXT NOT NULL CHECK (kyc_status IN ('VERIFIED', 'PENDING', 'NONE'))
);

INSERT INTO account_profile (account_id, display_name, kyc_status) VALUES
    ('john', 'John Kamau', 'VERIFIED'),
    ('alice', 'Alice Wanjiru', 'VERIFIED'),
    ('mary', 'Mary Achieng', 'VERIFIED'),
    ('bob', 'Bob Otieno', 'VERIFIED'),
    ('grace', 'Grace Muthoni', 'VERIFIED'),
    ('peter', 'Peter Njoroge', 'PENDING');

INSERT INTO account_profile (account_id, display_name, kyc_status)
SELECT 'bidder-' || lpad(n::text, 2, '0'), 'Bidder ' || n, 'VERIFIED' FROM generate_series(1, 20) AS n;
