# 10. Design decisions

Each entry gives the decision, why, and what it costs. They are the questions reviewers ask most.

## D1. Balances are derived from movements, never stored as truth

**Decision.** Ownership is a sum of signed movements. The `holding` table is a projection kept by a database
trigger, and everything can be rebuilt from the Action Ledger.

**Why.** Every balance can be explained line by line, an auditor can recompute it independently, and the replay test
proves the projection matches history.

**Cost.** More rows than a balance table. Reads stay fast because `holding` is maintained incrementally rather than
summed on every read.

## D2. One commit for both ledgers and the outbox; publish after

**Decision.** The event, its movements, the derived holding, the workflow projection, the stored reply and the
outbox row are written in one PostgreSQL transaction. Messages leave only after the commit.

**Why.** There is no state where the ledger says one thing and a message says another. A crash before commit loses
nothing that was promised; a crash after commit is recovered by the poller.

**Cost.** Delivery is at-least-once, so every consumer must be idempotent.

## D3. Invariants are database constraints, not workflow code

**Decision.** The ownership cap, the supply ceiling and "no overdraft" are `CHECK` constraints on the derived
holding row. The engine records the movement and lets the database refuse it.

**Why.** Application-level checks ("read the balance, check, then write") race under concurrency. A `CHECK` runs on
the value that actually commits, under the row lock taken by the increment. The bid storm proves it with 200
parallel bids.

**Cost.** The error comes back as a constraint name, so `CommandRunner.translate` maps each one to a business code.

## D4. Reservations move units into the buyer's reserved bucket

**Decision.** A reservation is a real movement (`@treasury` → buyer `RESERVED`), not a flag.

**Why.** The cap must count pending bids, otherwise a buyer could hold ten reservations of 19% each. Making reserved
units part of the buyer's holding lets the same `CHECK` enforce it.

## D5. The Action Ledger is hash-chained, with a single chain lock

**Decision.** Events get a gapless `seq` and `hash = sha256(prev_hash ‖ event)`, assigned under one advisory lock.

**Why.** Any change to history breaks the chain from that point; inclusion proofs let a third party check an event.

**Cost.** Appends are serialised across the whole platform: the lock is held from the append to the commit, so
write throughput is bounded by one transaction at a time. That is fine for the demo; it has not been load-tested.
The scaling path is a chain per token, or batching events into blocks with a Merkle root.

## D6. Every write is idempotent, and rejections are stored too

**Decision.** Every write carries an `Idempotency-Key`. The reply, success or refusal, is stored in the same
transaction and returned for any retry. The same key with a different body is a 409.

**Why.** Mobile networks and payment providers retry. "Same key, same reply" means a retry can never double an
award or a purchase, and a client that retries a refused request gets the same refusal rather than a different
outcome because the state moved on.

## D7. Rejections are ledger events

**Decision.** A refused request is recorded as `REQUEST_REJECTED` (with the code, reason and what was asked), in its
own transaction after the failed one rolls back.

**Why.** The brief asks for failures to be visible. "Why was Peter refused?" and "how many bids hit the cap?" have
answers in the same place as everything else.

**Cost.** The ledger grows with failed attempts; abusive clients are bounded by the gateway (rate limits in
production) and validation errors (400) are not recorded.

## D8. Rules are versioned data evaluated in a sandbox

**Decision.** Loyalty rules are JSON documents with SpEL expressions, published as events, evaluated in a context
with no types, constructors, methods or beans.

**Why.** The business changes rules without a deployment; every award names the version that decided it; simulation
over history is exact; a published rule cannot execute code.

**Alternative considered.** A full rules engine (Drools, DMN). Heavier than the brief needs; the seam is
`RuleEngine`.

## D9. Overlap resolution is explicit

**Decision.** Rules run by descending priority; every match adds; `stopOnMatch` ends evaluation.

**Why.** "What happens when two rules match?" is the most common rules bug. Making the order and the stopping rule
part of the document, and showing the trace, turns it into a visible choice (100 vs 101 points for KES 60,000).

## D10. Policy is checked twice: before and inside the transaction

**Decision.** The policy decision point runs before the transaction (cheap refusal, no locks) and again after the
token row lock.

**Why.** The first check keeps refused requests away from the locks; the second closes the gap where a customer is
frozen between the check and the commit.

## D11. Personal data stays out of the immutable ledger

**Decision.** Personal data is in `customer_pii`; the ledger stores only its digest. Customer profiles are operational
data, not a replayed projection.

**Why.** Data protection law requires correction and erasure, which an immutable log cannot do. The digest still
proves what was verified.

## D12. The console never computes a balance

**Decision.** The console shows only what the API derives.

**Why.** One source of truth. A screen that adds up numbers itself can disagree with the ledger, and in a demo about
provable ownership that would undermine the claim.

## D13. No Kafka or Redis for the demo

**Decision.** An outbox poller with in-process subscribers stands in for Kafka; a SQL count stands in for Redis.

**Why.** The brief's guarantees do not depend on either: correctness comes from the single commit and the database
constraints. Leaving them out keeps the demo to one process and one database. The seams (`OutboxSubscriber`,
`PolicyDecisionPoint`) are where they plug in.
