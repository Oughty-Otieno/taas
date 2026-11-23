"use client";

import { useEffect, useRef, useState } from "react";
import { Action, call, get, kes, label, newKey, num, Result, time, write } from "@/lib/api";
import { Check, Outcome } from "@/components/ui";

type StepState = "idle" | "active" | "done" | "failed" | "reversed";
type Step = { title: string; detail?: string; state: StepState };

function Pipeline({ steps }: { steps: Step[] }) {
  return (
    <div className="pipeline">
      {steps.map((s, i) => (
        <div key={i} className={`step ${s.state === "idle" ? "" : s.state}`}>
          <span className="n">{i + 1}</span>
          <div>
            <div className="step-title">{s.title}</div>
            {s.detail && <div className="step-detail">{s.detail}</div>}
          </div>
        </div>
      ))}
    </div>
  );
}

function useAccounts() {
  const [accounts, setAccounts] = useState<any[]>([]);
  useEffect(() => {
    get<any[]>("/v1/accounts").then((a) => setAccounts(a.filter((x) => !x.accountId.startsWith("bidder")))).catch(() => {});
  }, []);
  return accounts;
}

/** Keeps a form's account one this sign-in can see: a customer sees only their own, not the default. */
function useValidAccount(accounts: any[], account: string, setAccount: (a: string) => void) {
  useEffect(() => {
    if (accounts.length && !accounts.some((a) => a.accountId === account)) setAccount(accounts[0].accountId);
  }, [accounts, account, setAccount]);
}

export default function Flows() {
  return (
    <>
      <h1>Two flows, one platform</h1>
      <p className="lede">
        A loyalty award and a fractional asset purchase take the same path: the same gateway, the same workflow engine,
        the same two ledgers written in one commit, the same outbox. Only the rule set and the token type differ.
      </p>
      <div className="shared">Gateway (OAuth2 scope) → Workflow Engine → one commit: event + movements + outbox → stream</div>
      <div className="grid-2">
        <LoyaltyFlow />
        <AssetFlow />
      </div>
      <BidStorm />
    </>
  );
}

// --- Loyalty -------------------------------------------------------------------------------------

function LoyaltyFlow() {
  const accounts = useAccounts();
  const [account, setAccount] = useState("john");
  useValidAccount(accounts, account, setAccount);
  const [amount, setAmount] = useState(1200);
  const [last, setLast] = useState<{ key: string; body: any } | null>(null);
  const [res, setRes] = useState<Result | null>(null);
  const [steps, setSteps] = useState<Step[]>(initialLoyalty());

  function initialLoyalty(): Step[] {
    return [
      { title: "POST /v1/awards", detail: "The client sends the transaction, never a token amount", state: "idle" },
      { title: "Gateway", detail: "Validate bearer token, scope token:award, Idempotency-Key", state: "idle" },
      { title: "Workflow engine: evaluate rules", detail: "Active rule set decides how many units", state: "idle" },
      { title: "One commit", detail: "TOKEN_EARNED + movements (@treasury → customer) + outbox row", state: "idle" },
      { title: "201 and the new balance", detail: "Read from the derived holding", state: "idle" },
    ];
  }

  async function send(again = false) {
    const key = again && last ? last.key : newKey("award");
    const body = again && last ? last.body : {
      accountId: account,
      transaction: { type: "TXN_SETTLED", amountKes: amount, merchantId: "demo-merchant", channel: "MPESA", reference: `TXN-${Date.now()}` },
    };
    setLast({ key, body });
    setSteps(initialLoyalty().map((s, i) => (i < 2 ? { ...s, state: "active" } : s)));
    const r = await write("/v1/awards", body, key);
    setRes(r);
    const b = r.body;
    if (r.replayed) {
      setSteps(initialLoyalty().map((s, i) => i === 1
        ? { ...s, state: "done", detail: `Key ${key.slice(0, 18)}… already answered: same reply from the idempotency store` }
        : i === 0 ? { ...s, state: "done" } : { ...s, state: "idle", detail: "Did not run a second time" }));
      return;
    }
    if (r.status >= 400) {
      setSteps(initialLoyalty().map((s, i) => (i < 2 ? { ...s, state: "done" } : i === 2 ? { ...s, state: "failed", detail: b.detail } : s)));
      return;
    }
    const matched = (b.trace ?? []).filter((t: any) => t.matched).map((t: any) => `${t.ruleId} (+${t.units})`).join(", ");
    const declined = r.status === 200;
    setSteps([
      { ...initialLoyalty()[0], state: "done" },
      { ...initialLoyalty()[1], state: "done" },
      { title: `Workflow engine: ${b.ruleSet}`, detail: matched ? `Matched ${matched}` : "No rule matched", state: declined ? "reversed" : "done" },
      { title: "One commit", detail: declined ? "AWARD_NOT_QUALIFIED recorded (no movements)" : `TOKEN_EARNED #${b.actionId.slice(0, 8)} + 2 movements + outbox row`, state: "done" },
      { title: `${r.status} ${declined ? "declined" : "and the new balance"}`, detail: declined ? b.detail : (b.balance ?? []).map((h: any) => `${h.accountId}: ${num(h.available)} ${h.tokenId}`).join(" · "), state: declined ? "reversed" : "done" },
    ]);
  }

  return (
    <div className="panel">
      <div className="spread"><h2>Loyalty award</h2><span className="pill pill-OK">LOYALTY_MPR · loyalty.default</span></div>
      <div className="row">
        <label className="field">Customer
          <select value={account} onChange={(e) => setAccount(e.target.value)}>
            {accounts.map((a) => <option key={a.accountId} value={a.accountId}>{a.displayName}</option>)}
          </select>
        </label>
        <label className="field">Settled spend (KES)<input type="number" min={0} value={amount} onChange={(e) => setAmount(Number(e.target.value))} /></label>
        <div className="row" style={{ alignSelf: "flex-end" }}>
          {[800, 1200, 60000].map((v) => <button key={v} className="ghost" onClick={() => setAmount(v)}>{num(v)}</button>)}
        </div>
      </div>
      <div className="row" style={{ marginTop: 10 }}>
        <button className="primary" onClick={() => send(false)}>Submit transaction</button>
        <button disabled={!last} onClick={() => send(true)}>Send again with the same key</button>
      </div>
      <Pipeline steps={steps} />
      {res && (
        <div className="muted" style={{ fontSize: 12 }}>
          HTTP {res.status}{res.replayed ? " · Idempotent-Replay: true" : ""} · Idempotency-Key <code>{res.key}</code>
        </div>
      )}
    </div>
  );
}

// --- Asset purchase ------------------------------------------------------------------------------

/** The purchase in progress, kept in the browser so leaving /flows and coming back finds it again. */
type Pending = { orderId: string; reservation: any; tokenId: string; account: string; units: number; amountKes: number; outcome: string };
const PENDING_KEY = "taas.flows.pending";
function readPending(): Pending | null {
  try { return JSON.parse(localStorage.getItem(PENDING_KEY) ?? "null"); } catch { return null; }
}
function writePending(p: Pending | null) {
  try { if (p) localStorage.setItem(PENDING_KEY, JSON.stringify(p)); else localStorage.removeItem(PENDING_KEY); } catch { /* storage unavailable */ }
}

function AssetFlow() {
  const accounts = useAccounts();
  const [assets, setAssets] = useState<any[]>([]);
  const [tokenId, setTokenId] = useState("RWA_APT001");
  const [account, setAccount] = useState("alice");
  useValidAccount(accounts, account, setAccount);
  const [units, setUnits] = useState(250);
  const [outcome, setOutcome] = useState("MANUAL");
  const [hold, setHold] = useState(60);
  const [prompt, setPrompt] = useState<{ orderId: string; amountKes: number } | null>(null);
  const [answering, setAnswering] = useState(false);
  // Set once the order settles before the hold ends: the reservation is no longer HELD, so the timer has nothing to do.
  const [settled, setSettled] = useState<string | null>(null);
  const [steps, setSteps] = useState<Step[]>(initialAsset());
  const [reservation, setReservationState] = useState<any>(null);
  const reservationRef = useRef<any>(null);
  const setReservation = (r: any) => { reservationRef.current = r; setReservationState(r); };
  const [order, setOrder] = useState<any>(null);
  const [now, setNow] = useState(Date.now());
  const poll = useRef<ReturnType<typeof setInterval> | null>(null);

  // Coming back to the page: pick up an order that is still waiting for payment.
  useEffect(() => {
    const p = readPending();
    if (!p) return;
    get(`/v1/orders/${p.orderId}`).then((s: any) => {
      if (s.order.status !== "AWAITING_PAYMENT") { writePending(null); return; }
      setTokenId(p.tokenId); setAccount(p.account); setUnits(p.units); setOutcome(p.outcome);
      setReservation(p.reservation);
      setOrder(s);
      const steps = initialAsset().map((x, i) => (i < 4 ? { ...x, state: "done" as const } : x));
      steps[2] = { ...steps[2], detail: `Reservation ${p.reservation.reservationId.slice(0, 8)} held until ${time(p.reservation.expiresAt)}` };
      steps[3] = { ...steps[3], detail: `Order ${p.orderId.slice(0, 8)} · 202 AWAITING_PAYMENT · resumed` };
      steps[4] = s.paymentPromptOpen
        ? { ...steps[4], state: "active", title: "Waiting for payment", detail: "The M-PESA prompt is still on the buyer's phone. Pay or cancel it below." }
        : { ...steps[4], state: "active", detail: "Waiting for the provider's signed callback…" };
      setSteps(steps);
      if (s.paymentPromptOpen) setPrompt({ orderId: p.orderId, amountKes: p.amountKes });
      watch(p.orderId, p.reservation.reservationId);
    }).catch(() => writePending(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    get<any[]>("/v1/tokens").then((t) => setAssets(t.filter((x) => x.token.kind === "ASSET"))).catch(() => {});
    const tick = setInterval(() => setNow(Date.now()), 500);
    return () => { clearInterval(tick); if (poll.current) clearInterval(poll.current); };
  }, []);

  function initialAsset(): Step[] {
    return [
      { title: "POST /v1/reservations", detail: "Gateway: scope token:trade; PDP: KYC", state: "idle" },
      { title: "Engine: lock the asset row", detail: "Database checks the 20% cap and the remaining supply", state: "idle" },
      { title: "Commit TOKEN_RESERVED", detail: "@treasury → buyer's RESERVED bucket; expiresAt set", state: "idle" },
      { title: "POST /v1/orders", detail: "ORDER_PLACED; the PSP is charged from the outbox, same key", state: "idle" },
      { title: "PSP callback (signed)", detail: "PAYMENT_RECEIVED via /v1/payments/callback", state: "idle" },
      { title: "Commit TOKEN_PURCHASED", detail: "RESERVED → AVAILABLE; ownership assigned", state: "idle" },
    ];
  }

  function patch(i: number, s: Partial<Step>) {
    setSteps((prev) => prev.map((x, j) => (j === i ? { ...x, ...s } : x)));
  }

  async function start() {
    if (poll.current) clearInterval(poll.current);
    setSteps(initialAsset());
    setOrder(null);
    setReservation(null);
    setPrompt(null);
    setSettled(null);
    writePending(null);
    patch(0, { state: "active" });
    const r = await write("/v1/reservations", { tokenId, accountId: account, units, holdSeconds: hold || undefined }, newKey("reserve"));
    if (r.status !== 201) {
      const atDb = ["OWNERSHIP_CAP", "SOLD_OUT"].includes(r.body.code);
      patch(0, { state: atDb ? "done" : "failed", detail: atDb ? undefined : `${r.status} ${r.body.code}: ${r.body.detail}` });
      if (atDb) patch(1, { state: "failed", detail: `${r.body.code}: ${r.body.detail}` });
      return;
    }
    patch(0, { state: "done" });
    patch(1, { state: "done", detail: `Cap and supply held · ${num(r.body.units)} units = ${kes(r.body.amountKes)}` });
    patch(2, { state: "done", detail: `Reservation ${r.body.reservationId.slice(0, 8)} held until ${time(r.body.expiresAt)}` });
    setReservation(r.body);
    if (outcome === "WAIT") {
      patch(3, { state: "active", detail: "Not ordering: waiting for the timer" });
      watch(null, r.body.reservationId);
      return;
    }
    await placeOrder(r.body.reservationId, r.body.amountKes);
  }

  async function placeOrder(reservationId: string, amountKes: number) {
    patch(3, { state: "active" });
    const o = await write("/v1/orders", { reservationId, paymentMethod: "MPESA", simulateOutcome: outcome }, newKey("order"));
    if (o.status !== 202) {
      patch(3, { state: "failed", detail: `${o.status} ${o.body.code}: ${o.body.detail}` });
      return;
    }
    const manual = outcome === "MANUAL";
    patch(3, { state: "done", detail: `Order ${o.body.orderId.slice(0, 8)} · 202 AWAITING_PAYMENT · ${manual ? "M-PESA prompt sent to the buyer's phone" : outcome === "NO_CALLBACK" ? "the customer never pays" : `PSP will answer ${outcome}`}` });
    patch(4, manual
      ? { state: "active", title: "Waiting for payment", detail: "The M-PESA prompt is on the buyer's phone. Pay or cancel it below." }
      : { state: "active" });
    if (manual) setPrompt({ orderId: o.body.orderId, amountKes });
    writePending({ orderId: o.body.orderId, reservation: reservationRef.current, tokenId, account, units, amountKes, outcome });
    watch(o.body.orderId, reservationId);
  }

  function watch(orderId: string | null, reservationId: string) {
    poll.current = setInterval(async () => {
      if (orderId) {
        const s = await get(`/v1/orders/${orderId}`);
        setOrder(s);
        const st = s.order.status;
        if (st === "AWAITING_PAYMENT") return;
        if (poll.current) clearInterval(poll.current);
        setPrompt(null);
        writePending(null);
        if (st === "PAID") setSettled("purchased");
        if (st === "PAYMENT_FAILED") setSettled("released after the failed payment");
        if (st === "PAID") {
          patch(4, { state: "done", title: "PSP callback (signed)", detail: "PAYMENT_RECEIVED via /v1/payments/callback" });
          patch(5, { state: "done", detail: `200 ownership assigned · ${s.portfolio.map((h: any) => `${h.tokenId}: ${num(h.available)}`).join(" · ")}` });
        } else if (st === "PAYMENT_FAILED") {
          patch(4, { state: "failed", title: "PSP callback (signed)", detail: "PAYMENT_FAILED: the customer cancelled the M-PESA prompt" });
          patch(5, { state: "reversed", title: "Compensation", detail: "Units returned from the buyer's RESERVED bucket to @treasury" });
        } else if (st === "REFUND_REQUIRED") {
          patch(4, { state: "failed", title: "PSP callback (signed)", detail: "Paid after the hold had ended: REFUND_REQUIRED" });
          patch(5, { state: "reversed", title: "No ownership: the payment is refunded", detail: "The units had already gone back to @treasury when the hold expired" });
        } else {
          patch(4, { state: "reversed", detail: "No payment before the hold ran out" });
          patch(5, { state: "reversed", title: "Timer wins: TOKEN_RESERVATION_EXPIRED", detail: "Reversing movement; the reservation stays visible in the ledger" });
        }
      } else {
        const list = await get<any[]>(`/v1/reservations?tokenId=${tokenId}&accountId=${account}&limit=5`);
        const r = list.find((x) => x.reservationId === reservationId);
        if (r && r.status !== "HELD") {
          if (poll.current) clearInterval(poll.current);
          patch(3, { state: "reversed", title: "Timer wins: TOKEN_RESERVATION_EXPIRED", detail: "Units returned to @treasury by a compensating movement" });
        }
      }
    }, 1000);
  }

  /** The buyer answers the M-PESA prompt; the fake PSP then calls back through the signed webhook. */
  async function answer(pay: boolean) {
    if (!prompt) return;
    setAnswering(true);
    const r = await call(`/v1/orders/${prompt.orderId}/payment-prompt`, { method: "POST", body: { pay } });
    setAnswering(false);
    if (r.status !== 202) {
      patch(4, { state: "failed", detail: `${r.status} ${r.body?.code ?? ""} ${r.body?.detail ?? ""}` });
      return;
    }
    setPrompt(null);
    patch(4, { state: "active", title: "PSP callback (signed)", detail: pay ? "PIN entered: waiting for the provider's signed callback…" : "Prompt cancelled: waiting for the provider's signed callback…" });
  }

  async function lateOrder() {
    if (!reservation) return;
    const o = await write("/v1/orders", { reservationId: reservation.reservationId, paymentMethod: "MPESA" }, newKey("late-order"));
    patch(3, { state: o.status === 202 ? "done" : "failed", detail: `Later order: ${o.status} ${o.body.code ?? ""} ${o.body.detail ?? ""}` });
  }

  const left = reservation ? Math.max(0, Math.round((new Date(reservation.expiresAt).getTime() - now) / 1000)) : null;
  const expired = left === 0;

  return (
    <div className="panel">
      <div className="spread"><h2>Fractional asset purchase</h2><span className="pill pill-info">{tokenId} · policy@v1</span></div>
      <div className="row">
        <label className="field">Asset
          <select value={tokenId} onChange={(e) => setTokenId(e.target.value)}>
            {assets.map((a) => <option key={a.token.tokenId} value={a.token.tokenId}>{a.token.name}</option>)}
          </select>
        </label>
        <label className="field">Buyer
          <select value={account} onChange={(e) => setAccount(e.target.value)}>
            {accounts.map((a) => <option key={a.accountId} value={a.accountId}>{a.displayName}{a.kycStatus !== "VERIFIED" ? " (KYC pending)" : ""}</option>)}
          </select>
        </label>
        <label className="field">Units<input type="number" min={1} value={units} onChange={(e) => setUnits(Number(e.target.value))} style={{ width: 100 }} /></label>
        <label className="field">Payment
          <select value={outcome} onChange={(e) => setOutcome(e.target.value)}>
            <option value="MANUAL">I pay on the phone (manual)</option>
            <option value="SUCCESS">M-PESA succeeds</option>
            <option value="FAIL">Payment fails</option>
            <option value="NO_CALLBACK">Never paid (timer wins)</option>
            <option value="WAIT">Reserve only, no order</option>
          </select>
        </label>
        <label className="field">Hold (s)<input type="number" min={10} value={hold} onChange={(e) => setHold(Number(e.target.value))} style={{ width: 80 }} /></label>
      </div>
      <div className="row" style={{ marginTop: 10 }}>
        <button className="primary" onClick={start}>Reserve and buy</button>
        {reservation && settled && <span className="pill pill-OK" title="The reservation is no longer held, so the expiry timer will not touch it">hold closed: {settled}</span>}
        {reservation && !settled && <span className={`pill ${expired ? "pill-REVERSED" : "pill-info"}`}>{expired ? "hold expired" : `hold ${left}s left`}</span>}
        {reservation && !settled && expired && <button onClick={lateOrder}>Try a later order</button>}
      </div>
      <Pipeline steps={steps} />
      {prompt && (
        <div className="mpesa-prompt">
          <div className="muted" style={{ fontSize: 12 }}>M-PESA · on {account}&apos;s phone</div>
          <div>Pay <b>{kes(prompt.amountKes)}</b> for {num(units)} units of {tokenId}?</div>
          <div className="muted" style={{ fontSize: 12 }}>
            {expired ? "The hold has ended: paying now shows what happens to a late payment." : `The hold ends in ${left}s. Pay before then and the hold closes; the timer then has nothing to release.`}
          </div>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="primary" disabled={answering} onClick={() => answer(true)}>Enter PIN and pay</button>
            <button className="danger" disabled={answering} onClick={() => answer(false)}>Cancel</button>
          </div>
        </div>
      )}
      {order && (
        <ul className="mini-list">
          {order.timeline.map((a: Action) => (
            <li key={a.id}><span><Outcome outcome={a.outcome} code={label(a.eventType)} /></span><span className="muted">#{a.seq} · {time(a.occurredAt)}</span></li>
          ))}
        </ul>
      )}
    </div>
  );
}

// --- Bid storm -----------------------------------------------------------------------------------

function BidStorm() {
  const [bids, setBids] = useState(200);
  const [buyers, setBuyers] = useState(4);
  const [units, setUnits] = useState(100);
  const [res, setRes] = useState<any>(null);
  const [busy, setBusy] = useState(false);

  async function run() {
    setBusy(true);
    setRes(null);
    const r = await call("/v1/dev/bid-storm", { method: "POST", body: { tokenId: "RWA_TWR014", bids, buyers, units, holdSeconds: 60 } });
    setRes(r.body);
    setBusy(false);
  }

  return (
    <div className="panel" style={{ marginTop: 16 }}>
      <div className="spread">
        <div>
          <h2>The concurrency test: {bids} parallel bids</h2>
          <div className="muted">
            Telecom Tower 014: 10,000 units, 20% cap (2,000 per holder). Bids run in parallel against the database; the
            cap and the supply are CHECK constraints on the derived holding, not reads in workflow code. Holds last 60s,
            so the storm can be rerun once they expire.
          </div>
        </div>
      </div>
      <div className="row" style={{ marginTop: 10 }}>
        <label className="field">Bids<input type="number" value={bids} min={1} max={1000} onChange={(e) => setBids(Number(e.target.value))} style={{ width: 90 }} /></label>
        <label className="field">Buyers<input type="number" value={buyers} min={1} max={20} onChange={(e) => setBuyers(Number(e.target.value))} style={{ width: 80 }} /></label>
        <label className="field">Units per bid<input type="number" value={units} min={1} onChange={(e) => setUnits(Number(e.target.value))} style={{ width: 100 }} /></label>
        <button className="primary" style={{ alignSelf: "flex-end" }} onClick={run} disabled={busy}>{busy ? "Bidding…" : `Fire ${bids} bids`}</button>
      </div>
      {res?.bids && (
        <>
          <div className="row" style={{ margin: "12px 0", gap: 18 }}>
            <div><div className="big">{num(res.accepted)}</div><div className="muted">accepted</div></div>
            {Object.entries(res.rejectedByCode).map(([code, n]) => (
              <div key={code}><div className="big" style={{ color: "var(--failed)" }}>{num(n as number)}</div><div className="muted">{code}</div></div>
            ))}
            <div><div className="big">{res.maxHolderPercent}%</div><div className="muted">largest holder (cap {num(res.capUnits)})</div></div>
            <div><div className="big">{num(res.oversold)}</div><div className="muted">oversold</div></div>
            <div><div className="big">{num(res.durationMs)} ms</div><div className="muted">wall time</div></div>
          </div>
          <div className="row" style={{ gap: 16 }}>
            <Check ok={res.capHeld}>no holder above 20%</Check>
            <Check ok={res.oversold === 0}>no oversell ({num(res.treasuryAvailable)} left in treasury)</Check>
            <Check ok={res.ledgerConsistent}>ledger verifies</Check>
          </div>
          <table style={{ marginTop: 12 }}>
            <thead><tr><th>Buyer</th><th className="num">Accepted</th><th className="num">Rejected</th><th className="num">Holding (derived)</th><th style={{ width: "40%" }}>Share of supply · cap 20%</th></tr></thead>
            <tbody>
              {res.perBuyer.map((b: any) => (
                <tr key={b.accountId}>
                  <td>{b.accountId}</td><td className="num">{b.accepted}</td><td className="num">{b.rejected}</td><td className="num">{num(b.holding)}</td>
                  <td>
                    <div className="bar">
                      <span style={{ width: `${Math.min(100, b.sharePercent * 4)}%` }} />
                      <i className="cap" style={{ left: "80%" }} title="20% cap" />
                    </div>
                    <span className="muted" style={{ fontSize: 11 }}>{b.sharePercent}%</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      {res?.detail && <div className="banner banner-bad">{res.detail}</div>}
    </div>
  );
}
