# 8. The console

The console (`console/`, Next.js 16 and React 19) is the operator and demo interface. It is a thin client:
**it never computes a balance**. Every figure on screen comes from the API's derived endpoints, and live updates
come from the Action Ledger's event stream.

Open <http://localhost:3000>. The first time, click **Load example data**.

## Signing in

The menu at the top right signs you in as a role (see [roles and scopes](06-security-and-kyc.md#who-can-do-what-roles-and-scopes)).
Choosing **Customer** asks which account. The page reloads with a token for that role. Screens a role cannot use say
so and name the missing scope instead of failing quietly. The choice is remembered in the browser.

## Screens

| Route | Screen | Who uses it | What it shows |
|---|---|---|---|
| `/` | **Token Command Centre** | everyone | Each token's supply, issued, available, reserved, redeemed and owners; value; a lifecycle strip; recent transactions; the live event tape |
| `/operations` | **Token operations** | operations, admin, demo | Forms for every movement: Issue, Earn (submit a transaction), Transfer, Redeem, Reserve → Order → Pay; open reservations with Release and Order buttons |
| `/flows` | **Two flows, one platform** | presenters | A loyalty award and an asset purchase side by side, step by step, through the same path; the payment-failure and timer-wins variants; the bid-storm concurrency test |
| `/customers` | **Customers & KYC** | operations, compliance, auditor, customers | Staff: the directory with filters and a "needs review" queue, registration, and a panel per customer with actions, holdings and KYC history. Customers: "My verification", their history and portfolio |
| `/ledger` | **Action Ledger Explorer** | auditor, admin, operations | Filterable event list. Each event opens a drawer: the decision, the movements, the affected balances, related events, the hash chain and the raw event. Integrity check (Verify chain) and the replay test |
| `/rules` | **Workflow Rule Studio** | rules manager | Version history; a form editor for the next version; **Try a transaction** with a rule-by-rule trace; **simulation** over past transactions (what was awarded vs the draft, by customer); Publish; the policy catalogue |

### Colours

Events are coloured by outcome everywhere (tape, explorer, timelines):

| Colour | Outcome | Examples |
|---|---|---|
| green | committed | earned, purchased, transferred, verified |
| grey | declined | award did not qualify, KYC referred |
| amber, shaded | reversed | reservation released or expired, revoked, KYC revoked |
| red, shaded | failed | request rejected, payment failed, refund required, KYC rejected |

## A 10-minute demo script

1. **Command Centre.** Load example data. Point out that every number is derived from the ledger; there is no
   balance column anywhere.
2. **Two flows, one platform.** Run the loyalty award (John spends KES 60,000 → 100 points under v7), then the asset
   purchase (reserve → order → the PSP calls back → owned). Same gateway, same engine, same ledgers; only the rule
   set and token type differ.
3. **The failure paths.** Run the purchase with **Payment fails** (units go back to the treasury), and with **Never paid (timer wins)**
   (the timer wins: `TOKEN_RESERVATION_EXPIRED`, and a later order fails cleanly). Show the red and blue events on
   the tape.
4. **Bid storm.** 200 parallel bids on the tower. Nobody ends above 20%; the refusals are `OWNERSHIP_CAP` from the
   database.
5. **Action Ledger.** Open a transfer: the reason, the rule version, the two movements, the hash link. Click
   **Verify chain**. Then **Delete balance tables** and **Replay**: every holding comes back identical.
6. **Rule Studio.** Draft v8 ("2 points per KES 1,000"), try a KES 60,000 transaction, simulate over history to see
   the cost per customer, then publish. The publication itself is a ledger event.
7. **Customers & KYC.** As Operations, register a customer and submit tier 1: verified about 2 s later by the provider.
   Ask for tier 2: referred. Sign in as Compliance and verify it. Sign in as that customer and try to act for
   another account: refused, and recorded.

## Configuration and build

| Setting | Default | Meaning |
|---|---|---|
| `NEXT_PUBLIC_TAAS_API` | `http://localhost:8087` | The backend's base URL (see `console/.env.example`) |

```bash
cd console
npm install
npm run dev          # http://localhost:3000
npm run build && npm start
```

## Code layout

| File | Contents |
|---|---|
| `lib/api.ts` | Sign-in state, token fetching, `get`/`write` helpers (writes add an `Idempotency-Key`), `useStream` (SSE), formatters |
| `components/ui.tsx` | Shared pieces: `Outcome` pill, `Stat`, `EventTape`, `Drawer`, `Hash`, `Json`, `Requires` (scope gate) |
| `app/nav.tsx` | Navigation and the sign-in menu |
| `app/*/page.tsx` | One file per screen |
| `app/globals.css` | All styles |
