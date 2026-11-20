-- =====================================================================
-- V3: customers and KYC.
--
-- account_profile is the customer directory the policy decision point
-- reads. It is operational data owned by onboarding, written in the same
-- transaction as the ledger event that records each change; the Action
-- Ledger holds the audit trail (CUSTOMER_REGISTERED, KYC_*), never the
-- personal data itself.
-- =====================================================================
ALTER TABLE account_profile DROP CONSTRAINT account_profile_kyc_status_check;
ALTER TABLE account_profile ADD CONSTRAINT account_profile_kyc_status_check
    CHECK (kyc_status IN ('NONE', 'PENDING', 'VERIFIED', 'REJECTED', 'FROZEN'));

ALTER TABLE account_profile
    ADD COLUMN kyc_tier      INT  NOT NULL DEFAULT 0 CHECK (kyc_tier BETWEEN 0 AND 2),
    ADD COLUMN kyc_case_id   UUID,
    ADD COLUMN kyc_tier_requested INT,
    ADD COLUMN kyc_review    TEXT CHECK (kyc_review IN ('AWAITING_PROVIDER', 'NEEDS_REVIEW')),
    ADD COLUMN kyc_note      TEXT,
    ADD COLUMN registered_at TIMESTAMPTZ,
    ADD COLUMN updated_at    TIMESTAMPTZ;

-- The customers seeded in V2 were onboarded before this system: fully verified.
UPDATE account_profile SET kyc_tier = 2 WHERE kyc_status = 'VERIFIED';
UPDATE account_profile SET kyc_status = 'NONE' WHERE account_id = 'peter';

-- Personal data, kept out of the immutable ledger so it can be corrected and
-- erased (Data Protection Act 2019). The ledger stores only a digest of it.
CREATE TABLE customer_pii (
    account_id    TEXT PRIMARY KEY REFERENCES account_profile (account_id),
    full_name     TEXT,
    phone         TEXT,
    national_id   TEXT,
    date_of_birth DATE,
    erased_at     TIMESTAMPTZ
);
