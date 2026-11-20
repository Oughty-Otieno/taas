# TaaS: Tokenisation as a Service

A platform that creates, tracks, transfers and retires digital units of value (loyalty points, and
fractional shares of real-world assets such as apartments, telecom towers and farms) and can always
prove **who owns what** and **why**.

> Balances are not stored as truth. Balances are calculated from history.

**Full documentation: [docs/](docs/README.md)** (overview, architecture, ledgers, workflows, rules and policy,
access and KYC, API reference, console guide, operations, design decisions). With the backend running, the live API
docs are at <http://localhost:8087/swagger-ui.html>.

| Component | Question it answers |
|---|---|
| Workflow Engine | What is allowed to happen? |
| Action Ledger | What happened, and why? |
| Token Ledger | Who owns what right now? |

## The transaction path

```
Next.js console / partner apps
        │  Authorization: Bearer <scoped token>, Idempotency-Key
        ▼
API gateway (Spring Security)  ── scope check, idempotency store, PSP signature check
        ▼
Workflow Engine  ── rule sets (versioned), policy decision point (KYC, velocity), timers
        ▼  one commit
PostgreSQL: action_event + token_movement (+ derived holding) + outbox
        ▼  polled after commit
Outbox → PSP charge · signed webhooks · SSE live stream   (Kafka slots in here)
```

A token transaction is complete and saved before anything is published. "Did the customer get their
token?" is answered by the ledger, never by a broker.

## The two ledgers

**Token Ledger: movements, not balances.** `token_movement` holds signed integer movements in an
`AVAILABLE` or `RESERVED` bucket, each citing the action that caused it and its rule version. The
movements of each action sum to zero. Units are created only at `@mint` (issuance into `@treasury`) and
retired only into `@burn`. Rows are never updated or deleted (a trigger refuses it); a mistake is
corrected by a compensating movement.

`holding` is the derived balance: an incremental projection the database maintains from the movements
with atomic increments in the same transaction. No application code writes it, and no write path reads
it to decide. Every screen and API read comes from it.

**Action Ledger: append-only and chained.** `action_event` records `event_type`, `subject_id`,
`actor_id`, `idempotency_key`, `rule_version` and the payload, with
`hash = sha256(prev_hash || canonical(event))`. UPDATE, DELETE and TRUNCATE are refused by triggers. In
production the application role is granted only INSERT and SELECT on it.

Events: `TOKEN_DEFINED · TOKEN_ISSUED · TOKEN_EARNED · AWARD_NOT_QUALIFIED · TOKEN_RESERVED ·
TOKEN_RESERVATION_RELEASED · TOKEN_RESERVATION_EXPIRED · ORDER_PLACED · PAYMENT_RECEIVED · PAYMENT_FAILED ·
PAYMENT_REFUND_REQUIRED · TOKEN_PURCHASED · TOKEN_TRANSFERRED · TOKEN_REDEEMED · TOKEN_REVOKED ·
RULE_VERSION_PUBLISHED · REQUEST_REJECTED`

Failures are first-class: a rejected transfer, a KYC deny, a cap breach and a velocity deny are each
recorded as `REQUEST_REJECTED` with the reason; expiry and payment failure are compensating events.

## Where each rule is enforced

| Policy | Example | Enforced at | Mechanism |
|---|---|---|---|
| Accrual, tiering | ≥ KES 1,000 → 1; ≥ KES 50,000 → 100 | Rules engine | Versioned rule set; explicit priority and `stopOnMatch` |
| Ownership cap | No holder above 20% of an asset | **Database** | `SELECT … FOR UPDATE` on the token row + `CHECK` on the derived holding (owned + reserved) |
| Supply ceiling | Issued ≤ authorised supply | **Database** | `CHECK` on the derived `@mint` holding |
| No overdraft | Can't spend what you don't hold | **Database** | `CHECK` on the derived holding |
| Reservation timeout | Unpaid bid released after 15 min | Workflow timer | `TOKEN_RESERVATION_EXPIRED`, a reversing movement |
| Loyalty expiry | Lapse after 12 months inactive | Workflow timer | `TOKEN_REVOKED` |
| Eligibility | Asset holders need verified KYC | Policy decision point | Denied before the workflow; re-checked at commit |
| Velocity | Max N transfers per hour | Policy decision point | Counter over the ledger (Redis in production); a deny is a ledger event |
| Authorisation | Caller may use this operation | Gateway | OAuth2 scope per route |

A reservation moves units from `@treasury` into the **buyer's** `RESERVED` bucket, so pending bids count
toward the buyer's cap. Two hundred parallel bids cannot push anyone past 20%, and cannot oversell.

**Rule overlap.** Rules run in descending `priority` (default 0), ties in document order. Every matching
rule adds its award, and a match on a rule with `stopOnMatch` ends evaluation. With the brief's example, a
KES 60,000 spend gets 100 units if `tier-high` stops, or 101 if it doesn't. The Rule Studio shows both.

## API

Every write takes an `Idempotency-Key` header (scoped to the calling client). The same key returns the
stored reply (same status and body, with `Idempotent-Replay: true`) without running the workflow again.
Rejections are stored too. The same key with a different body is a 409.

| Method | Path | Scope | Notes |
|---|---|---|---|
| GET | /v1/tokens · /v1/tokens/{id} | token:read | Supply, issued, available, reserved, redeemed, owners: all derived |
| GET | /v1/accounts/{id}/portfolio | token:read | Holdings across loyalty and asset tokens |
| POST | /v1/tokens | token:issue | Define a token (writes `TOKEN_DEFINED`) |
| POST | /v1/issuances | token:issue | Mint into the treasury |
| POST | /v1/awards | token:award | Submit a qualifying transaction; the rules decide. No amount is accepted |
| POST · DELETE | /v1/reservations · /v1/reservations/{id} | token:trade | Reserve units for a bid (returns `expiresAt`) · release early |
| POST · GET | /v1/orders · /v1/orders/{id} | token:trade | Confirm a bid (202; PSP charged from the outbox) · workflow state |
| POST | /v1/transfers | token:transfer | Holder to holder; KYC, velocity and cap apply |
| POST | /v1/redemptions | token:redeem | Burn units |
| POST | /v1/payments/callback | HMAC signature | PSP webhook: `X-PSP-Timestamp` + `X-PSP-Signature`; replay-safe |
| GET | /v1/ledger/actions · /{id} · /{id}/proof | ledger:read | Filter by token, actor, account, asset type, type, time; cursor paged; hash-chain inclusion proof |
| GET | /v1/ledger/movements · /v1/ledger/verify · /v1/ledger/stream | ledger:read | Raw movements; integrity check; SSE of committed events |
| POST | /v1/ledger/replay · /v1/ledger/wipe | ledger:admin | The replay test (wipe is a demo endpoint) |
| GET · POST | /v1/rules · /v1/rules/versions | rules:read · rules:publish | Rule sets are versioned documents; publishing is a ledger event |
| POST | /v1/rules/simulate | rules:read | Dry-run a rule set over recorded transactions |
| GET · POST | /v1/customers · /v1/customers/{id} | customer:read · customer:register | Customer directory (masked) with KYC history · register |
| POST · DELETE | /v1/customers/{id}/kyc · /kyc/decision · /pii | kyc:submit · kyc:review | Submit for KYC · compliance decision or freeze · erase personal data |
| POST | /v1/kyc/callback | HMAC signature | KYC provider webhook: `X-KYC-Timestamp` + `X-KYC-Signature` |
| POST | /v1/dev/token · /v1/dev/seed · /v1/dev/bid-storm | demo | Dev token issuer (sign in as a role), example data, 200 parallel bids |

Rejections answer with `{status, code, detail, actionId}`. Codes include `OWNERSHIP_CAP`, `SOLD_OUT`,
`SUPPLY_CEILING`, `INSUFFICIENT_UNITS`, `KYC_REQUIRED` (403), `VELOCITY_LIMIT` (429) and
`RESERVATION_EXPIRED` (409).

## Running it

Prerequisites: Java 17+, Maven, PostgreSQL 14+, Node 20+.

```bash
createuser taas_user -P            # password: taas_pass
createdb taas -O taas_user
createdb taas_test -O taas_user    # for the tests

mvn spring-boot:run                # API on http://localhost:8087

cd console && npm install && npm run dev   # console on http://localhost:3000
```

Open the console and click **Load example data**. Then go to **Two Flows, One Platform**.

Settings (environment): `TAAS_DB_URL`, `TAAS_DB_USER`, `TAAS_DB_PASSWORD`, `TAAS_PORT`,
`TAAS_JWT_SECRET`, `TAAS_PSP_SECRET`, `TAAS_WEBHOOK_URLS`, `TAAS_WEBHOOK_SECRET`, `TAAS_CORS_ORIGINS`,
`TAAS_MAX_TRANSFERS_PER_HOUR`, `TAAS_KYC_TIER1_LIMIT_KES`, `TAAS_KYC_SECRET`, and `TAAS_DEMO_ENDPOINTS` (set `false` to remove the dev token issuer, the
seed, the bid storm and the wipe endpoint). The console reads `NEXT_PUBLIC_TAAS_API`.

## The console

| Route | Screen | What it proves |
|---|---|---|
| `/` | Token Command Centre | Every figure comes from the ledger's derived endpoints; a live lifecycle strip and event tape |
| `/flows` | Two flows, one platform | A loyalty award and an asset purchase take the same path; the timer-wins and payment-failure paths; 200 parallel bids |
| `/ledger` | Action Ledger Explorer | Every movement has a reason: filters, drawer with raw event, movements, balances, decision, related events, inclusion proof; Verify Chain; the replay test |
| `/operations` | Token operations | Issue, earn, transfer, redeem, reserve → order → pay, from forms |
| `/customers` | Customers & KYC | Registration, tiered KYC with a provider and compliance review, freezing, erasure; a customer's own view |
| `/rules` | Workflow Rule Studio | Rules are versioned data; edit, simulate against history (old vs new), publish |

The console never computes a balance. Failures (rejections, failed payments) and reversals (expiries,
releases) are coloured differently in the tape.

## Tests

`mvn test` runs against the `taas_test` database. The suite covers:

- **The brief's examples:** John → Alice; Issue 100 / Transfer 20 / Redeem 10 = 70.
- **Replay test:** delete every balance table, replay, get identical holdings and workflow state.
- **History:** immutable (UPDATE and DELETE refused), tamper detection that blocks replay, and inclusion proofs.
- **Idempotency:** the same key 5× in parallel records once; the same key with a different body gets a 409; a stored rejection is replayed.
- **Concurrency:** 200 parallel bids never breach the 20% cap or oversell. The cap counts reservations and applies to transfers. The supply ceiling holds.
- **Asset purchase:**
  - reserve → order → signed callback → purchase;
  - bad or stale signatures are refused;
  - payment failure returns the units;
  - when the timer wins, a later order gets a 409 and a late payment records a refund.
- **Rules:**
  - v7 tiers; overlap resolution;
  - declined awards are recorded;
  - publishing is a ledger event;
  - code-execution expressions are refused;
  - simulation compares a proposed rule set with history.
- **Policy:** KYC deny recorded, velocity limit, loyalty inactivity revocation.
- **Access:** scopes are enforced (401/403); a customer can act only for their own account.
- **Customers and KYC:** onboarding to tier 1, tier-2 referral decided by compliance, provider rejection, revoke
  freezes, erasure leaves no personal data in the ledger, unsigned callbacks refused.

## Not built yet

- Kafka and Redis. The outbox poller and a ledger-backed counter stand in for them; the seams are
  `OutboxSubscriber` and `PolicyDecisionPoint`.
- A real identity provider (the decoder takes a JWKS URI instead of the dev key), and customer self-registration.
- A real KYC provider; two-person approval for issuing supply and publishing rules.
- mTLS on the PSP and KYC callbacks (deployment configuration).
- A database role restricted to INSERT/SELECT on `action_event`. Flyway and the app share one user
  locally, so the triggers are the guard.
