# TaaS documentation

TaaS (Tokenisation as a Service) creates, tracks, transfers and retires digital units of value: loyalty points,
and fractional shares of real-world assets such as apartments, telecom towers and farms. It can always prove
**who owns what** and **why**.

## Reading order

| # | Document | Read it if you want to know | Audience |
|---|---|---|---|
| 1 | [Overview](01-overview.md) | What the platform does, the vocabulary, and the claims it makes | Everyone |
| 2 | [Architecture](02-architecture.md) | The components, the transaction path, and where each piece of code lives | Engineers |
| 3 | [The two ledgers](03-ledgers.md) | How ownership and history are stored, the invariants, and the replay test | Engineers, auditors |
| 4 | [Workflows](04-workflows.md) | Loyalty, asset purchase, expiry, payment failure, transfers, redemption, timers | Engineers, product |
| 5 | [Rules and policy](05-rules-and-policy.md) | Versioned rule sets, overlap resolution, simulation, the policy decision point | Product, rules managers, engineers |
| 6 | [Access, customers and KYC](06-security-and-kyc.md) | Sign-in, roles and scopes, account binding, onboarding, KYC tiers, personal data | Compliance, security, engineers |
| 7 | [API reference](07-api.md) | Every endpoint, idempotency, error codes, worked `curl` examples | Integrators, engineers |
| 8 | [The console](08-console.md) | Each screen, who can use it, and a demo script | Product, operations, presenters |
| 9 | [Running, testing and extending](09-operations.md) | Setup, configuration, tests, troubleshooting, how to add a feature | Engineers |
| 10 | [Design decisions](10-decisions.md) | Why it is built this way, and what was deliberately left out | Architects, reviewers |

## The three sentences to remember

1. **Balances are calculated from history, not stored as truth.** The Action Ledger is the record; everything
   else is a projection that can be deleted and rebuilt (the replay test does exactly that).
2. **One path for every movement.** Gateway → Workflow Engine → one database commit covering both ledgers and the
   outbox. Messages are published only after the commit.
3. **The database enforces the invariants.** The 20% ownership cap, the supply ceiling and "no overdraft" are
   `CHECK` constraints, so no amount of concurrency or application bugs can breach them.

## Live API documentation

With the backend running, the OpenAPI UI is at <http://localhost:8087/swagger-ui.html> (and `/` redirects
there). Use **Authorize** with a token from `POST /v1/dev/token` (see [the API reference](07-api.md#getting-a-token)).
