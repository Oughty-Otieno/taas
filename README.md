# TaaS — Tokenization as a Service

A platform that creates, tracks, transfers and retires digital units of value (loyalty
points, rewards, fractional shares of real-world assets) and can always prove **who owns
what** and **why**.

## The one rule

> Balances are not stored as truth. Balances are calculated from history.

No code path may write a balance directly. A balance is a projection of the event history,
and it must be rebuildable from that history at any time.

## Components

| Component | Answers | Responsibility |
|---|---|---|
| Workflow Engine | "Should this happen, and how?" | Business rules: earn rates, issuance, transfers, redemptions, timed reservations. Decides; never touches balances. |
| Token Ledger | "Who owns what right now?" | Double-entry postings and the balance projection built from them. |
| Action Ledger | "Why did ownership change?" | Immutable, ordered, append-only event log. The source of truth. |

All sit behind an API gateway exposing one operation set:
`issue`, `earn`, `reserve`, `transfer`, `purchase`, `redeem`.

## Invariants

1. **Derived balances.** `balance(account, token) = Σ postings` — never edited.
2. **Recorded movement.** Every movement has, committed atomically:
   - balanced postings in the Token Ledger (sum of all legs = 0),
   - a matching event in the Action Ledger,
   - a unique transaction id (idempotency key); a repeat returns the original result.
3. **Replay.** Delete all balance tables, keep the Action Ledger, replay events in sequence
   order, and get identical balances (verified by comparison).

## Design decisions

- **Event sourcing.** The Action Ledger is the record; balances are a projection.
- **Double-entry.** Issuance credits a holder against a per-token `MINT` account; redemption
  debits a holder into `BURN`. Circulating supply = −balance(MINT) − balance(BURN), so it
  is always reconcilable.
- **Strict ordering.** A global monotonic sequence number on events makes replay deterministic.
- **Reservations are events.** `RESERVED` → `RESERVATION_RELEASED` | `RESERVATION_EXPIRED` |
  `PURCHASED`. Available = owned − held. Expiry is written as an event, so replay matches.
- **Corrections append.** Mistakes are fixed with reversing events, never by editing history.
- **Tamper evidence.** Each event stores a hash of the previous event (hash chain).

## Worked example (replay test)

| # | Event | John | Alice | MINT | BURN | Supply |
|---|---|---|---|---|---|---|
| 1 | Issue 100 to John | 100 | 0 | −100 | 0 | 100 |
| 2 | Transfer 20 John → Alice | 80 | 20 | −100 | 0 | 100 |
| 3 | John redeems 10 | 70 | 20 | −100 | 10 | 90 |

The brief's "balance is 70" is John's; Alice holds 20; circulating supply is 90.

## How the code maps to the design

| Concept | Where |
|---|---|
| Action Ledger (append-only, hash-chained; DB triggers refuse UPDATE/DELETE/TRUNCATE) | `ledger/ActionLedger.java`, `V1__ledgers.sql` |
| Posting rules: the one place an event becomes balance changes | `ledger/PostingRules.java` |
| Token Ledger: postings, balances, statements, supply | `ledger/TokenLedger.java` |
| Projector: applies events live **and** on replay (same code path) | `ledger/Projector.java` |
| Workflow Engine: rules, idempotency, one transaction per operation | `workflow/WorkflowEngine.java` |
| Reservation expiry (recorded as an event) | `workflow/ReservationExpiryJob.java` |
| Gateway check (`X-API-Key`) | `api/ApiKeyFilter.java` |

Every operation runs in one transaction: take the ledger lock → settle the `txId` → check the
rules → append the event → project it. All of it commits or none of it does. Writes are serialised
by one Postgres advisory lock, which keeps sequence numbers gapless and the hash chain linear (fine
for a prototype; per-token chains would be the way to scale out).

## Running it

Needs Java 17+ and Postgres (local dev uses Postgres.app).

```bash
psql -h localhost -d postgres -c "CREATE ROLE taas_user LOGIN PASSWORD 'taas_pass'" \
  -c "CREATE DATABASE taas OWNER taas_user" -c "CREATE DATABASE taas_test OWNER taas_user"
mvn test                      # 19 tests, against taas_test
mvn spring-boot:run           # http://localhost:8087, API key: dev-key
```

Override with `TAAS_DB_URL`, `TAAS_DB_USER`, `TAAS_DB_PASSWORD`, `TAAS_API_KEYS`, `TAAS_PORT`.

## API

All `/api` calls need `X-API-Key`. Every write takes a caller-chosen `txId`: a new one answers
**201**; the same `txId` with the same body answers **200** with the original event and
`Idempotent-Replay: true` and moves nothing; the same `txId` with a different body is **409**.
Rule violations (overdraft, supply cap, expired reservation) are **422**.

| Method | Path | Body |
|---|---|---|
| POST | `/api/tokens` | `txId, code, name, kind (LOYALTY/ASSET), kesPerToken?, reservationMinutes? (15), maxSupply?` |
| POST | `/api/tokens/{code}/issue` | `txId, to, amount, reference?` |
| POST | `/api/tokens/{code}/earn` | `txId, account, spendKes, reference?` — earns `spendKes / kesPerToken` |
| POST | `/api/tokens/{code}/transfer` | `txId, from, to, amount, reference?` |
| POST | `/api/tokens/{code}/reserve` | `txId, seller, buyer, amount` — escrows for the reservation window |
| POST | `/api/tokens/{code}/reservations/{id}/purchase` | `txId, buyer, paymentRef` |
| POST | `/api/tokens/{code}/reservations/{id}/release` | `txId` |
| POST | `/api/tokens/{code}/redeem` | `txId, account, amount, reference?` |
| GET | `/api/tokens`, `/api/tokens/{code}` (with supply), `/api/tokens/{code}/holders` | |
| GET | `/api/accounts/{account}/balances` | available + reserved for sale |
| GET | `/api/accounts/{account}/statement?token=` | every movement with running balance |
| GET | `/api/events?token=&afterSeq=&limit=`, `/api/events/{txId}` | the Action Ledger |
| POST | `/api/admin/replay` | wipe all projections, rebuild from the Action Ledger, compare |
| GET | `/api/admin/verify` | hash chain + balance table vs postings + double-entry check |

### Try the brief's example

```bash
K='X-API-Key: dev-key'; J='Content-Type: application/json'; B=localhost:8087/api
curl -sH "$K" -H "$J" $B/tokens -d '{"txId":"c1","code":"PTS","name":"Points","kind":"LOYALTY","kesPerToken":1000}'
curl -sH "$K" -H "$J" $B/tokens/PTS/issue -d '{"txId":"t1","to":"john","amount":100}'
curl -sH "$K" -H "$J" $B/tokens/PTS/transfer -d '{"txId":"t2","from":"john","to":"alice","amount":20}'
curl -sH "$K" -H "$J" $B/tokens/PTS/redeem -d '{"txId":"t3","account":"john","amount":10}'
curl -sH "$K" "$B/accounts/john/statement?token=PTS"     # 100 -> 80 -> 70
psql -h localhost -U taas_user -d taas -c "DELETE FROM balance; DELETE FROM posting; DELETE FROM reservation; DELETE FROM token"
curl -sH "$K" -X POST $B/admin/replay                    # rebuilds john 70, alice 20
```

When the projections were wiped first, the replay report shows `balancesIdentical: false` because
there was nothing left to compare against; check the holders afterwards. Over intact projections
it reports `true`.

## Tests

`TaasApiTest` covers the brief's scenarios end to end: the John/Alice transfer, the statement,
the replay test (delete every balance table, replay, same balances), idempotent retries and reused
txIds, the earn rule, overdraft and supply-cap refusals, reservation purchase/release/15-minute
expiry, the API key, immutability of history, tamper detection (an edited event breaks the hash
chain and replay refuses to run), and 25 concurrent transfers that never overdraw.

## Status

Prototype built 2026-11-19: Spring Boot 4.1.1, Java 17, Postgres, Flyway. Not yet: per-client keys
and scopes, per-token sharding of the lock, a UI.
