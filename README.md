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

## Status

Folder created 2026-11-19. Stack and implementation not yet chosen.
