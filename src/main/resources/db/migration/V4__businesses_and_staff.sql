-- =====================================================================
-- V4: businesses (issuers) and staff users.
-- =====================================================================

-- A business is the company behind a token: the issuer of an asset or the
-- operator of a loyalty programme. A projection of BUSINESS_* events,
-- rebuilt by replay like the token catalogue. Only public company data is
-- kept here, so all of it may appear in the ledger.
CREATE TABLE business (
    business_id     TEXT        PRIMARY KEY,
    name            TEXT        NOT NULL,
    registration_no TEXT        NOT NULL UNIQUE,
    sector          TEXT,
    payout_account  TEXT,
    description     TEXT,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'SUSPENDED')),
    status_reason   TEXT,
    registered_by   TEXT        NOT NULL,
    registered_seq  BIGINT      NOT NULL,
    registered_at   TIMESTAMPTZ NOT NULL,
    updated_seq     BIGINT      NOT NULL
);

-- Which business issued a token. Tokens defined before businesses existed
-- get theirs from TOKEN_ISSUER_ASSIGNED.
ALTER TABLE token ADD COLUMN issuer_id TEXT REFERENCES business (business_id);
CREATE INDEX token_issuer_idx ON token (issuer_id);

-- Named staff users. Operational data like the customer directory: written
-- in the same transaction as the STAFF_* event that records each change, and
-- not rebuilt by replay. The ledger records the user id, name and role, never
-- the email address.
CREATE TABLE staff_user (
    user_id      TEXT        PRIMARY KEY,
    display_name TEXT        NOT NULL,
    email        TEXT,
    role         TEXT        NOT NULL CHECK (role IN ('admin', 'rules-manager', 'operations', 'compliance', 'auditor')),
    status       TEXT        NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    status_note  TEXT,
    created_by   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

-- One user per role to start with.
INSERT INTO staff_user (user_id, display_name, role, status, created_by, created_at, updated_at) VALUES
    ('amina.yusuf', 'Amina Yusuf', 'admin', 'ACTIVE', 'migration', now(), now()),
    ('brian.odhiambo', 'Brian Odhiambo', 'rules-manager', 'ACTIVE', 'migration', now(), now()),
    ('faith.chebet', 'Faith Chebet', 'operations', 'ACTIVE', 'migration', now(), now()),
    ('david.mwangi', 'David Mwangi', 'compliance', 'ACTIVE', 'migration', now(), now()),
    ('lucy.atieno', 'Lucy Atieno', 'auditor', 'ACTIVE', 'migration', now(), now());
