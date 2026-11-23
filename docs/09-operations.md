# 9. Running, testing and extending

## Prerequisites

Java 17+, Maven 3.9+, PostgreSQL 14+ (developed on 17), Node 20+.

## First-time setup

```bash
createuser taas_user -P            # password: taas_pass
createdb taas -O taas_user         # the application
createdb taas_test -O taas_user    # the test suite (wiped by every test)
```

Flyway creates the schema on start-up (`src/main/resources/db/migration`).

## Running

```bash
mvn spring-boot:run                          # API on http://localhost:8087, Swagger at /swagger-ui.html
cd console && npm install && npm run dev     # console on http://localhost:3000
```

Then open the console and click **Load example data**.

To run a second backend beside your own (for example, to try something without touching your data), point it at
another port and database:

```bash
TAAS_PORT=8099 TAAS_DB_URL=jdbc:postgresql://localhost:5432/taas_test mvn spring-boot:run
```

## Configuration

All settings are environment variables with development defaults. **Every secret's default is for development
only**; set real values anywhere else.

| Variable | Default | Meaning |
|---|---|---|
| `TAAS_DB_URL` | `jdbc:postgresql://localhost:5432/taas` | Database |
| `TAAS_DB_USER`, `TAAS_DB_PASSWORD` | `taas_user`, `taas_pass` | Database credentials |
| `TAAS_PORT` | `8087` | HTTP port |
| `TAAS_JWT_SECRET` | dev value | HS256 key for access tokens (32+ characters) |
| `TAAS_DEMO_ENDPOINTS` | `true` | `false` removes the dev token issuer, personas, seed, bid storm and wipe |
| `TAAS_CORS_ORIGINS` | `http://localhost:3000` | Origins allowed to call the API from a browser |
| `TAAS_MAX_TRANSFERS_PER_HOUR` | `20` | Velocity limit per sender |
| `TAAS_KYC_TIER1_LIMIT_KES` | `100000` | Largest purchase a tier-1 customer may make |
| `TAAS_PSP_SECRET` | dev value | HMAC key for payment callbacks |
| `TAAS_KYC_SECRET` | dev value | HMAC key for KYC callbacks |
| `TAAS_WEBHOOK_URLS` | (none) | Comma-separated receivers of every committed event |
| `TAAS_WEBHOOK_SECRET` | dev value | HMAC key for outgoing webhooks |

Other settings in `application.yml`: timer intervals (`taas.timers.*`), outbox poll interval, and the fake PSP and
KYC provider (`auto-callback`, `callback-delay-ms`).

## Tests

```bash
mvn test          # 50 tests against the taas_test database
```

`PlatformTestBase` truncates every table before each test and resets the seeded customers. Tests use a
`MutableClock`, so they can move time forward (to expire a reservation, or age a loyalty holding) without waiting.
The test profile switches off the background timers, the outbox poller and the fake providers' automatic
callbacks; tests drive them explicitly.

| Suite | Count | Covers |
|---|---|---|
| `LedgerTest` | 8 | The brief's examples (John → Alice; issue 100 / transfer 20 / redeem 10 = 70); replay rebuilds identical holdings and workflow state; UPDATE and DELETE refused; tamper detection blocks replay; inclusion proofs; idempotency (5 parallel requests, one record; a different body gets 409; a stored rejection replays) |
| `WorkflowTest` | 19 | 200 parallel bids never breach the cap or oversell; the cap counts reservations and applies to transfers; supply ceiling; reserve → order → signed callback → purchase; bad and stale signatures refused; payment failure returns units; the timer wins, a later order gets 409 and a late payment records a refund; KYC deny recorded; velocity; loyalty inactivity; scopes enforced (401/403) |
| `RulesTest` | 7 | v7 tiers and overlap resolution; declined awards recorded; publishing is a ledger event; code-execution expressions refused; simulation; evaluate |
| `BusinessStaffTest` | 8 | Four-eyes business verification; assets only under a verified issuer; a suspended issuer cannot issue or sell while holders keep their rights; naming an issuer for an older token, rebuilt by replay; staff act under their own name with email kept out of the ledger; disabling or re-roling revokes a token at once; nobody changes themselves and the last admin stays; the sign-in menu lists active users only |
| `CustomerTest` | 8 | Account binding; role scopes; onboarding to tier 1; tier-2 referral decided by compliance; provider rejection; revoke freezes; erasure (no personal data in the ledger); unsigned callback refused |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `401 A valid bearer token is required` on `/` | You opened an API route without a token. Use `/swagger-ui.html` (or `/`, which redirects there) |
| Console says "connecting" and shows no data | The backend is not running on `NEXT_PUBLIC_TAAS_API`, or the console's origin is not in `TAAS_CORS_ORIGINS` |
| A screen is missing from the nav, or its address opens the Command Centre | Your sign-in lacks that screen's scope (customers see only their own screens); switch role with the sign-in menu |
| `VERSION_EXISTS` when publishing rules | Versions are immutable; publish a new version number |
| `Ledger integrity failure` on replay | The hash chain is broken (an event was changed outside the application). Run `GET /v1/ledger/verify` to find the first broken `seq` |
| Flyway fails at start-up after pulling | A migration changed after it was applied locally. For a disposable dev database: `dropdb taas && createdb taas -O taas_user` |
| Purchase stays `AWAITING_PAYMENT` | The order used `NO_CALLBACK`, or `taas.payments.fake-psp.auto-callback` is off. It expires when the hold runs out |

## How to extend

### Add a new kind of movement (a new event type)

1. Add the type to `EventType`, with its outcome (OK, DECLINED, REVERSED or FAILED).
2. Give it a rule in `MovementRules.legsFor`. The compiler forces this: the `switch` is exhaustive. Its legs must sum
   to zero.
3. If it changes a workflow projection (reservation, order…), handle it in `Projector.apply`.
4. Add a method to `WorkflowEngine` that runs through `runner.execute(...)`, locks the token, and records the event.
   Do not read a balance to decide; let the database constraints refuse.
5. Expose it in a controller, add its route and scope to `SecurityConfig`, and add a test, including one that
   replays.

Because live writes and replay share `Projector.apply`, the replay test covers the new event automatically.

### Add a policy

Add a method to `PolicyDecisionPoint` that throws a `Rejection` with a code, and call it from the workflow's policy
`Runnable` (which runs before the transaction and again inside it). It is recorded as `REQUEST_REJECTED` for free.

### Add a new invariant

If it is about holdings (a limit per holder or per token), make it a `CHECK` on `holding` in a new Flyway
migration, and map the constraint name to a business code in `CommandRunner.translate`.

### Add a new token type or rule field

New asset types need only a `POST /v1/tokens`. A new rule input (such as `event.category`) must be added where the
event map is built in `WorkflowEngine.award` and `RulesController.evaluate`, recorded in the `TOKEN_EARNED` payload
(so simulation can use it), and included in `RuleEngine.check`'s sample event.

### Add a downstream integration

Implement `OutboxSubscriber`. It receives every committed event at least once, so make it idempotent (the event `id`
or the order's idempotency key are natural de-duplication keys).

## Production checklist

| Item | Today | Needed |
|---|---|---|
| Identity | Dev token endpoint, shared HS256 secret | An identity provider; the JWT decoder on its JWKS URI; `TAAS_DEMO_ENDPOINTS=false` |
| Database roles | One user for Flyway and the app | The app role granted only `INSERT, SELECT` on `action_event` and `token_movement` |
| Secrets | Development defaults | Real secrets from a vault for JWT, PSP, KYC and webhooks |
| Callbacks | HMAC + 5-minute window | Also mTLS with the PSP and KYC provider |
| Messaging | Outbox poller, in-process subscribers | Kafka (or CDC on `outbox`), with consumers keyed on the event id |
| Counters | Velocity is a SQL count | Redis |
| Approvals | One person can issue supply and publish rules | Two-person (maker-checker) approval |
| Ledger anchoring | Hash chain in the same database | Periodically publish the head hash somewhere independent |
| Console | Next.js 16.4.2 | Upgrade to the patched Next.js release (a security advisory affects this version) |
