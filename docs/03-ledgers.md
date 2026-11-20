# 3. The two ledgers

## The Action Ledger: what happened and why

Table `action_event`. One row per thing that happened: a token defined, units issued, a point earned, a bid
reserved, a payment failed, a request refused, a customer verified.

| Column | Meaning |
|---|---|
| `id` | UUID of the action. Every movement cites it. |
| `seq` | Gapless sequence number, assigned under the chain lock |
| `event_type` | One of the types [below](#event-types) |
| `subject_id` | What the event is about: a token, a rule set or an account |
| `token_id` | The token involved, if any |
| `actor_id` | Who caused it: the client id from the access token (`console`, `operations@taas`, `customer:alice`), or a system actor (`psp`, `kyc-provider`, `timer:reservation-expiry`) |
| `idempotency_key` | The key the request carried |
| `rule_version` | Which rule set or policy decided it, e.g. `loyalty.default@v7`, `policy:RWA_APT001@v1`, `kyc-policy@v1` |
| `payload` | The facts as a flat JSON object of strings (account, units, amount, reason, …) |
| `prev_hash`, `hash` | `hash = sha256(prev_hash ‖ canonical(event))`; the first event's `prev_hash` is 64 zeros |
| `occurred_at` | When it was recorded (microsecond precision, so the hash recomputes identically) |

**It cannot be changed.** Triggers refuse `UPDATE`, `DELETE` and `TRUNCATE`. In production the application's
database role would also be granted only `INSERT` and `SELECT`. Locally Flyway and the app share one user, so the
triggers are the guard.

**It is tamper-evident.** Each hash covers the previous one, so changing any old event (for example by a DBA with
the triggers disabled) breaks every hash after it. `GET /v1/ledger/verify` walks the chain; replay refuses to run
over a broken chain. `GET /v1/ledger/actions/{id}/proof` returns the event, its recomputed hash and every link
from it to the head (up to 5,000 links), which a third party can verify independently.

### Event types

The outcome decides the colour on screen: successes, declines, reversals and failures look different.

| Event | Outcome | Movements (from → to) |
|---|---|---|
| `TOKEN_DEFINED` | OK | none (creates the `token` row) |
| `TOKEN_ISSUED` | OK | `@mint` → `@treasury` |
| `TOKEN_EARNED` | OK | `@treasury` → customer |
| `AWARD_NOT_QUALIFIED` | declined | none |
| `TOKEN_RESERVED` | OK | `@treasury` → buyer's **reserved** bucket |
| `TOKEN_RESERVATION_RELEASED` | reversed | buyer reserved → `@treasury` |
| `TOKEN_RESERVATION_EXPIRED` | reversed | buyer reserved → `@treasury` |
| `ORDER_PLACED` | OK | none |
| `PAYMENT_RECEIVED` | OK | none |
| `PAYMENT_FAILED` | failed | buyer reserved → `@treasury` |
| `PAYMENT_REFUND_REQUIRED` | failed | none (money must go back; no units moved) |
| `TOKEN_PURCHASED` | OK | buyer reserved → buyer available |
| `TOKEN_TRANSFERRED` | OK | sender → receiver |
| `TOKEN_REDEEMED` | OK | customer → `@burn` |
| `TOKEN_REVOKED` | reversed | customer → `@treasury` (loyalty inactivity) |
| `RULE_VERSION_PUBLISHED` | OK | none (creates a `rule_set_version` row) |
| `CUSTOMER_REGISTERED` | OK | none |
| `KYC_SUBMITTED` | OK | none |
| `KYC_REFERRED` | declined | none |
| `KYC_VERIFIED` | OK | none |
| `KYC_REJECTED` | failed | none |
| `KYC_REVOKED` | reversed | none |
| `PII_ERASED` | OK | none |
| `BUSINESS_REGISTERED` | OK | none (creates a `business` row) |
| `BUSINESS_VERIFIED` | OK | none (also reinstates a suspended business) |
| `BUSINESS_SUSPENDED` | reversed | none |
| `TOKEN_ISSUER_ASSIGNED` | OK | none (names the issuer of a token defined without one) |
| `STAFF_USER_CREATED` | OK | none |
| `STAFF_ROLE_CHANGED` | OK | none |
| `STAFF_USER_DISABLED` | reversed | none |
| `STAFF_USER_ENABLED` | OK | none |
| `REQUEST_REJECTED` | failed | none (records the refused request, its code and reason) |

The mapping from event to movements is in exactly one place, `MovementRules.legsFor`. The live write path and the
replay both use it, which is why replay always produces the same holdings.

## The Token Ledger: who owns what

### Movements

Table `token_movement`. Ownership is never stored as a number to overwrite; it is the sum of signed movements.

```
action #42  TOKEN_TRANSFERRED  john → alice, 20 LOYALTY_MPR
   movement  LOYALTY_MPR  john   AVAILABLE  -20  action=#42  rule=policy:LOYALTY_MPR@v1
   movement  LOYALTY_MPR  alice  AVAILABLE  +20  action=#42  rule=policy:LOYALTY_MPR@v1
```

- Units are signed `BIGINT`s, never floats, never zero.
- Each movement is in one of two buckets: `AVAILABLE` (the holder's to use) or `RESERVED` (held for a pending
  purchase).
- **The movements of one action sum to zero.** Units are created only at `@mint` and retired only into `@burn`, so
  the total across all accounts is always zero, and `-@mint` is exactly the number of units ever issued.
- Rows are never updated or deleted (a trigger refuses it). A mistake is corrected with a compensating movement.

### Holdings: the derived balance

Table `holding`, one row per (token, account), with `available` and `reserved`. It is maintained by the
`apply_movement` trigger with atomic increments **in the same transaction** as the movement. No application code
writes it, and no write path reads it to make a decision. Every screen and API read comes from it.

The brief's example, *Issue 100 / Transfer 20 / Redeem 10*:

| Action | `@mint` | `@treasury` | john | alice | `@burn` |
|---|---|---|---|---|---|
| Issue 100 | −100 | +100 | | | |
| Earn 100 (john) | | −100 | +100 | | |
| Transfer 20 to alice | | | −20 | +20 | |
| Redeem 10 | | | −10 | | +10 |
| **Holding** | **−100** | **0** | **70** | **20** | **10** |

### The invariants live in the database

| Constraint on `holding` | Rule | Error code returned |
|---|---|---|
| `holding_no_overdraft` | `available ≥ 0` (except `@mint`) | `INSUFFICIENT_UNITS`, or `SOLD_OUT` when it is the treasury |
| `holding_reserved_non_negative` | `reserved ≥ 0` | same as above |
| `holding_supply_ceiling` | on `@mint`: `available ≥ −authorised_supply` | `SUPPLY_CEILING` |
| `holding_ownership_cap` | on customers of capped tokens: `available + reserved ≤ max_units` | `OWNERSHIP_CAP` |

`max_units` is set by the `holding_limits` trigger when a holding row is first created:
`authorised_supply × max_holder_bps / 10000` (20% → 2,000 of `RWA_TWR014`'s 10,000 units).

Why this is safe under concurrency: the trigger's `UPDATE holding SET available = available + n` takes a row lock,
and the `CHECK` runs on the value that will actually commit. Two concurrent bids cannot both see "room left" and
both succeed. The engine never computes "is there room?"; it just records the movement and lets the database
refuse it. `CommandRunner.translate` turns the constraint name into a business code and the refusal is recorded
as `REQUEST_REJECTED`.

**The cap counts reservations.** A reservation moves units into the *buyer's* reserved bucket, so pending bids
count toward their 20%. A buyer cannot hold several reservations that together exceed the cap.

## Projections and the replay test

Everything except the Action Ledger is either derived from it, or explicitly not.

| Table | Derived from the ledger? | Rebuilt by replay? |
|---|---|---|
| `token` | yes (`TOKEN_DEFINED`, `TOKEN_ISSUER_ASSIGNED`) | yes |
| `business` | yes (`BUSINESS_*`) | yes |
| `token_movement`, `holding` | yes | yes |
| `reservation`, `purchase_order` | yes | yes |
| `rule_set_version` | yes (`RULE_VERSION_PUBLISHED`) | yes |
| `idempotency_record` | no: stored replies | no |
| `outbox` | no: delivery bookkeeping | no |
| `staff_user` | no: operational, like the customer directory; the ledger keeps every change | no |
| `account_profile`, `customer_pii` | no: operational customer data (see [KYC](06-security-and-kyc.md#why-customer-data-is-not-a-projection)) | no |

**Replay** (`POST /v1/ledger/replay`, scope `ledger:admin`), in one transaction:

1. Lock the `token` table, then the chain, so nothing writes meanwhile.
2. Snapshot the current holdings and workflow state.
3. Truncate every projection.
4. Read every event in `seq` order. For each, check `prev_hash` and recompute `hash`. If either is wrong, abort; the
   rollback leaves the projections exactly as they were.
5. Apply each event with the same `Projector.apply` the live path uses.
6. Compare the rebuilt holdings and workflow state with the snapshot.

The report says how many events and movements were replayed, whether the chain is valid, and whether holdings and
workflow state are identical, with any differences listed.

**Wipe** (`POST /v1/ledger/wipe`, demo only) deletes the projections without rebuilding, so the console can show the
balances disappearing and replay bringing them back. Replay after a wipe compares with the state from before the
wipe.

**Verify** (`GET /v1/ledger/verify`, read-only) checks the hash chain, that every holding equals the sum of its
movements, and that every action's movements sum to zero.
