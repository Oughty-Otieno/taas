# 7. API reference

Base URL locally: `http://localhost:8087`. Interactive docs: <http://localhost:8087/swagger-ui.html>.

## Conventions

| Convention | Detail |
|---|---|
| Authentication | `Authorization: Bearer <JWT>` on every call except the callbacks and the public routes ([details](06-security-and-kyc.md#authentication)) |
| Idempotency | Every write (`POST`, `DELETE`) needs `Idempotency-Key`: 1–100 of `A-Z a-z 0-9 . _ : -`. Keys are scoped to the calling client |
| Same key, same reply | A repeated key returns the stored status and body with `Idempotent-Replay: true`, without running the workflow again. Stored rejections are replayed too |
| Same key, different request | `409 Idempotency key reused`, not recorded |
| Units and money | Integers. Units are whole token units; money is whole KES |
| Errors | RFC 7807 problem JSON: `{title, status, code, detail, actionId, seq}`. `actionId` is the `REQUEST_REJECTED` event, when one was recorded |
| Times | ISO-8601 UTC instants |

## Getting a token

```bash
# Sign in as a named staff user (demo only); the ledger then records their actions under their id
curl -s -X POST localhost:8087/v1/dev/token -H 'Content-Type: application/json' -d '{"userId":"amina.yusuf"}'

# Sign in as a role (demo only). Personas: demo, admin, rules-manager, operations, compliance, auditor, customer
TOKEN=$(curl -s -X POST localhost:8087/v1/dev/token -H 'Content-Type: application/json' \
  -d '{"persona":"operations"}' | jq -r .access_token)

# A customer is bound to one account
ALICE=$(curl -s -X POST localhost:8087/v1/dev/token -H 'Content-Type: application/json' \
  -d '{"persona":"customer","accountId":"alice"}' | jq -r .access_token)

# Or a client-credentials style token with chosen scopes, for a partner system
curl -s -X POST localhost:8087/v1/dev/token -H 'Content-Type: application/json' \
  -d '{"clientId":"pos-gateway","scopes":["token:award"]}'
```

Tokens last 12 hours. `GET /v1/dev/personas` lists the roles and their scopes.

## Endpoints

### Tokens and supply

| Method | Path | Scope | Body / notes |
|---|---|---|---|
| GET | `/v1/tokens` | `token:read` | Every token with authorised, issued, available, reserved, redeemed and owner counts, all derived |
| GET | `/v1/tokens/{tokenId}` | `token:read` | One token, with its holders and recent activity; a customer gets only their own holding and the activity involving their account |
| POST | `/v1/tokens` | `token:issue` | `{tokenId, name, kind: LOYALTY\|ASSET, assetType?, backing?, valuationKes? (assets: must equal authorisedSupply × unitPriceKes; worked out when omitted), unitPriceKes (assets), redeemValueKes?, authorisedSupply, maxHolderPercent?, reservationMinutes?, kycRequired?, inactivityMonths?, ruleSetId?}` → `TOKEN_DEFINED` |
| POST | `/v1/issuances` | `token:issue` | `{tokenId, units, reference?}` → `TOKEN_ISSUED` |
| GET | `/v1/accounts/{accountId}/portfolio` | `token:read` | Holdings across every token (customers: their own only) |

### Loyalty, trading, transfers

| Method | Path | Scope | Body / notes |
|---|---|---|---|
| POST | `/v1/awards` | `token:award` | `{accountId, ruleSetId?, transaction: {type, amountKes, merchantId?, channel?, reference?}}`. No amount of units is accepted; the rules decide → `TOKEN_EARNED` or `AWARD_NOT_QUALIFIED` |
| POST | `/v1/reservations` | `token:trade` | `{tokenId, accountId, units, holdSeconds?}` → 201 `{reservationId, amountKes, expiresAt}` |
| GET | `/v1/reservations?accountId=&tokenId=&status=` | `token:trade` | Reservations (customers: their own), each with its order if one was placed: `orderId`, `orderStatus`, `paymentPromptOpen` |
| DELETE | `/v1/reservations/{id}` | `token:trade` | Release early → `TOKEN_RESERVATION_RELEASED` |
| POST | `/v1/orders` | `token:trade` | `{reservationId, paymentMethod?: MPESA, simulateOutcome?: SUCCESS\|FAIL\|NO_CALLBACK\|MANUAL}` → 202 `AWAITING_PAYMENT` |
| POST | `/v1/orders/{id}/payment-prompt` | `token:trade` | Demo: `{pay: true\|false}` answers a `MANUAL` order's M-PESA prompt as the buyer (a customer only their own) → 202; the fake PSP then sends its signed callback. 404 when no prompt is waiting |
| GET | `/v1/orders/{id}` | `token:trade` | `{order, reservation, step, timeline, portfolio, paymentPromptOpen}`: the workflow state, in words, with its events |
| POST | `/v1/transfers` | `token:transfer` | `{tokenId, fromAccountId, toAccountId, units, reference?}` → `TOKEN_TRANSFERRED` |
| POST | `/v1/redemptions` | `token:redeem` | `{tokenId, accountId, units, merchantId?, reference?}` → 201 `{redemptionId, kesPerUnit, valueKes, rateSource, payout: {status: PENDING, to}}`; `TOKEN_REDEEMED` |
| GET | `/v1/redemptions/{id}` | `token:redeem` | The receipt: units, rate and its source, value, and `payout.status` `PENDING`/`PAID`/`FAILED` with the M-PESA reference (a customer only their own) |
| POST | `/v1/payments/payout-callback` | HMAC | `{pspEventId, redemptionId, status: SUCCEEDED\|FAILED, amountKes, pspReference?, reason?}` → `PAYOUT_SENT` / `PAYOUT_FAILED` |
| POST | `/v1/payments/callback` | HMAC | `{pspEventId, orderId, status: SUCCEEDED\|FAILED, amountKes, pspReference?, reason?}` with `X-PSP-Timestamp`, `X-PSP-Signature` |

`simulateOutcome` is a demo hook for the fake PSP: `FAIL` makes it decline, `NO_CALLBACK` makes it never answer so
the timer wins, and `MANUAL` leaves the M-PESA prompt waiting on the buyer's phone until they answer it with
`POST /v1/orders/{id}/payment-prompt`. Paying after the hold has ended gives `REFUND_REQUIRED`.

### Customers and KYC

| Method | Path | Scope | Body / notes |
|---|---|---|---|
| GET | `/v1/customers` | `customer:read` or `kyc:submit` | The directory (masked); a customer sees only themselves |
| GET | `/v1/customers/{id}` | `customer:read` or `kyc:submit` | Profile plus KYC history from the ledger, and the tier-1 limit |
| POST | `/v1/customers` | `customer:register` | `{accountId?, fullName, phone, nationalId, dateOfBirth}` → `CUSTOMER_REGISTERED` |
| POST | `/v1/customers/{id}/kyc` | `kyc:submit` | `{tier: 1\|2}` → 202 `KYC_SUBMITTED` |
| POST | `/v1/customers/{id}/kyc/decision` | `kyc:review` | `{decision: VERIFY\|REJECT\|REVOKE, tier?, reason}` |
| DELETE | `/v1/customers/{id}/pii` | `kyc:review` | Erase personal data → `PII_ERASED` |
| POST | `/v1/kyc/callback` | HMAC | `{providerEventId, caseId, accountId, result: VERIFIED\|REJECTED\|REFERRED, tier?, reason?}` with `X-KYC-Timestamp`, `X-KYC-Signature` |

### Businesses and staff

| Method | Path | Scope | Body / notes |
|---|---|---|---|
| GET | `/v1/businesses` · `/v1/businesses/{id}` | `token:read` | Directory with each business's tokens · detail with its ledger history |
| POST | `/v1/businesses` | `business:manage` | `{businessId?, name, registrationNo, sector?, payoutAccount?, description?}` → `BUSINESS_REGISTERED` (PENDING) |
| POST | `/v1/businesses/{id}/decision` | `business:review` | `{decision: VERIFY \| SUSPEND, reason}` (reason required to suspend) |
| POST | `/v1/tokens/{tokenId}/issuer` | `business:manage` | `{issuerId}` → `TOKEN_ISSUER_ASSIGNED` |
| GET | `/v1/staff` · `/v1/staff/{id}` | `staff:manage` | Users · one user with the changes to their account and their recent actions |
| POST | `/v1/staff` | `staff:manage` | `{userId?, displayName, email?, role}` → `STAFF_USER_CREATED` |
| POST | `/v1/staff/{id}/role` | `staff:manage` | `{role}` → `STAFF_ROLE_CHANGED` |
| POST | `/v1/staff/{id}/status` | `staff:manage` | `{status: ACTIVE \| DISABLED, reason}` (reason required to disable) |

`POST /v1/tokens` also accepts `issuerId`, the business issuing the token.

### Action Ledger

| Method | Path | Scope | Notes |
|---|---|---|---|
| GET | `/v1/ledger/actions` | `ledger:read` | Filters: `tokenId`, `actorId`, `account`, `assetType`, `type` (comma-separated), `from`, `to`, `related` (reservation or order id). Paging: `cursor` (a `seq`), `limit` (default 50), `order=newest\|oldest` |
| GET | `/v1/ledger/actions/{id}` | `ledger:read` | One event (its movements: `/v1/ledger/movements?actionId=`) |
| GET | `/v1/ledger/actions/{id}/proof` | `ledger:read` | Recomputed hash and the chain links from this event to the head |
| GET | `/v1/ledger/movements?tokenId=&accountId=&actionId=&limit=` | `ledger:read` | The raw signed movements |
| GET | `/v1/ledger/stream` | `ledger:read` | Server-sent events of committed actions. `EventSource` cannot set headers, so `?access_token=` is accepted |
| GET | `/v1/ledger/verify` | `ledger:read` | Hash chain, holdings vs movements, zero-sum per action |
| POST | `/v1/ledger/replay` | `ledger:admin` | The replay test ([details](03-ledgers.md#projections-and-the-replay-test)) |
| POST | `/v1/ledger/wipe` | `ledger:admin` | Demo only: delete the projections so replay can rebuild them |

### Rules

| Method | Path | Scope | Notes |
|---|---|---|---|
| GET | `/v1/rules` | `rules:read` | Rule sets with their versions and the one in force; the policy catalogue; the overlap-resolution statement |
| POST | `/v1/rules/versions` | `rules:publish` | `{document}` → `RULE_VERSION_PUBLISHED` |
| POST | `/v1/rules/evaluate` | `rules:read` | `{transaction, accountId?, document? \| ruleSetId?}` → awarded units and the rule-by-rule trace |
| POST | `/v1/rules/simulate` | `rules:read` | `{document, from?, to?}` → proposed vs actual over recorded decisions |

### Demo and development (only with `TAAS_DEMO_ENDPOINTS=true`)

| Method | Path | Scope | Notes |
|---|---|---|---|
| GET | `/v1/dev/personas` | public | The sign-in roles |
| GET | `/v1/dev/users` | public | The active staff users, for the sign-in menu |
| POST | `/v1/dev/token` | public | Issue a token ([above](#getting-a-token)) |
| POST | `/v1/dev/seed` | `ledger:admin` | Example data. Fixed keys, so running it twice changes nothing |
| POST | `/v1/dev/bid-storm` | `ledger:admin` | `{tokenId?, bids?, buyers?, units?, holdSeconds?}` parallel reservations |

## Error codes

| HTTP | Code | Meaning |
|---|---|---|
| 400 | (no code) | Invalid request: missing field, bad account id, missing `Idempotency-Key`. Not recorded |
| 401 | | Missing, invalid or expired token; or a bad callback signature |
| 403 | | The token lacks the route's scope, or a customer read another account |
| 403 | `ACCOUNT_NOT_PERMITTED` | A customer tried to act for another account |
| 403 | `KYC_REQUIRED` | The account is not KYC-verified, and the token requires it |
| 403 | `KYC_TIER_TOO_LOW` | Tier 1 and the purchase is above the tier-1 limit |
| 403 | `ACCOUNT_FROZEN` | The account is frozen pending review |
| 404 | | Unknown token, reservation, order, customer or action |
| 409 | `RESERVATION_EXPIRED` | The hold ran out before the order |
| 409 | `RESERVATION_RELEASED`, `RESERVATION_PURCHASED`, `RESERVATION_EXPIRED`, `RESERVATION_PAYMENT_FAILED` | The reservation is already closed |
| 409 | `ORDER_EXISTS`, `ORDER_AWAITING_PAYMENT`, `ORDER_ALREADY_SETTLED` | Order state conflicts |
| 409 | `TOKEN_EXISTS`, `VERSION_EXISTS`, `ACCOUNT_EXISTS` | Already defined, published or registered |
| 409 | `KYC_IN_PROGRESS`, `ALREADY_VERIFIED`, `CASE_NOT_OPEN`, `NO_OPEN_CASE`, `NOT_VERIFIED`, `NOTHING_TO_ERASE` | KYC case state conflicts |
| 409 | (no code) | Idempotency key reused with a different request; or the ledger's hash chain is broken |
| 422 | `OWNERSHIP_CAP` | The database refused: the holder would exceed the cap (owned + reserved) |
| 422 | `SOLD_OUT` | The database refused: not enough units left in the treasury |
| 422 | `INSUFFICIENT_UNITS` | The database refused: the account does not hold enough units |
| 422 | `SUPPLY_CEILING` | The database refused: issuing would exceed the authorised supply |
| 422 | `NO_ACTIVE_RULES` | The rule set has no version in force |
| 422 | `UNDERAGE`, `NO_PERSONAL_DATA` | Onboarding rules |
| 422 | `ISSUER_NOT_VERIFIED` | Assets can be listed only under a verified business |
| 403 | `ISSUER_SUSPENDED` | The token's business is suspended: no new issuance or sales |
| 403 | `FOUR_EYES` | The person who registered a business cannot verify it |
| 403 | `CANNOT_CHANGE_SELF` | Staff cannot change their own role or status |
| 409 | `BUSINESS_EXISTS`, `REGISTRATION_EXISTS`, `ISSUER_ALREADY_SET`, `USER_EXISTS`, `NO_CHANGE`, `LAST_ADMIN` | Business and staff conflicts |
| 429 | `VELOCITY_LIMIT` | Too many transfers in the last hour |

Every coded rejection (403/409/422/429 with a `code`) is recorded as `REQUEST_REJECTED`.

## Worked example: the brief's story in `curl`

```bash
API=localhost:8087
T=$(curl -s -X POST $API/v1/dev/token -H 'Content-Type: application/json' -d '{"persona":"demo"}' | jq -r .access_token)
H=(-H "Authorization: Bearer $T" -H 'Content-Type: application/json')

curl -s -X POST $API/v1/dev/seed "${H[@]}" -H 'Idempotency-Key: seed-1' > /dev/null

# John spends KES 60,000: the rules decide he earns 100
curl -s -X POST $API/v1/awards "${H[@]}" -H 'Idempotency-Key: txn-2001' \
  -d '{"accountId":"john","transaction":{"type":"TXN_SETTLED","amountKes":60000,"merchantId":"naivas","reference":"TXN-2001"}}'

# Retry the same request: same reply, Idempotent-Replay: true, nothing recorded twice
curl -si -X POST $API/v1/awards "${H[@]}" -H 'Idempotency-Key: txn-2001' \
  -d '{"accountId":"john","transaction":{"type":"TXN_SETTLED","amountKes":60000,"merchantId":"naivas","reference":"TXN-2001"}}' \
  | grep -i idempotent-replay

# Alice buys 250 apartment units
RES=$(curl -s -X POST $API/v1/reservations "${H[@]}" -H 'Idempotency-Key: res-a1' \
  -d '{"tokenId":"RWA_APT001","accountId":"alice","units":250}' | jq -r .reservationId)
ORD=$(curl -s -X POST $API/v1/orders "${H[@]}" -H 'Idempotency-Key: ord-a1' \
  -d "{\"reservationId\":\"$RES\"}" | jq -r .orderId)
sleep 2   # the fake PSP calls back after ~1.5 s
curl -s $API/v1/orders/$ORD "${H[@]}" | jq .order.status   # "PAID"

# Grace tries to take more than 20% of the tower: refused by the database
curl -s -X POST $API/v1/reservations "${H[@]}" -H 'Idempotency-Key: res-g1' \
  -d '{"tokenId":"RWA_TWR014","accountId":"grace","units":2500}' | jq '{status, code, detail}'

# Prove it: rebuild every balance from history and compare
curl -s -X POST $API/v1/ledger/replay "${H[@]}" | jq '{eventsReplayed, hashChainValid, holdingsIdentical, workflowIdentical}'
```

## Webhooks

Set `TAAS_WEBHOOK_URLS` (comma-separated) to receive every committed event as a `POST` with
`X-TaaS-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(TAAS_WEBHOOK_SECRET, t + "." + body)>`. Delivery is
at-least-once; de-duplicate on the event `id`.
