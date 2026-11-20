"use client";

import { useCallback, useEffect, useState } from "react";
import { Action, call, can, get, kes, label, newKey, num, Result, Session, time, useSession, write } from "@/lib/api";
import { Drawer, Requires } from "@/components/ui";

type Customer = {
  accountId: string; displayName: string; kycStatus: string; kycTier: number; kycCaseId: string | null;
  kycTierRequested: number | null; kycReview: string | null; kycNote: string | null; phoneMasked: string | null;
  nationalIdMasked: string | null; piiErased: boolean; registeredAt: string | null; updatedAt: string | null;
};
type Detail = { customer: Customer; history: Action[]; tier1LimitKes: number };

const STATUS_CLASS: Record<string, string> = { VERIFIED: "pill-OK", PENDING: "pill-info", NONE: "pill-DECLINED", REJECTED: "pill-FAILED", FROZEN: "pill-REVERSED" };

function KycBadge({ c }: { c: Customer }) {
  return (
    <span className="row" style={{ gap: 4 }}>
      <span className={`pill ${STATUS_CLASS[c.kycStatus] ?? ""}`}>{c.kycStatus.toLowerCase()}{c.kycTier ? ` · tier ${c.kycTier}` : ""}</span>
      {c.kycReview === "AWAITING_PROVIDER" && <span className="pill pill-info">checking tier {c.kycTierRequested}</span>}
      {c.kycReview === "NEEDS_REVIEW" && <span className="pill pill-REVERSED">needs review</span>}
    </span>
  );
}

export default function CustomersPage() {
  const session = useSession();
  return (
    <>
      <h1>Customers &amp; KYC</h1>
      <p className="lede">
        Onboarding and identity verification. Personal data stays in a separate store that can be corrected and erased;
        the Action Ledger records only that each step happened, by whom, with a digest of the data. Holding an asset
        token needs verified KYC: tier 1 (identity) allows purchases up to a limit, tier 2 (address and source of funds)
        has none.
      </p>
      <Requires anyOf={["customer:read", "kyc:submit"]} what="This screen is for staff who serve customers, and for customers themselves.">
        {session && (session.accountId ? <MyVerification session={session} /> : <StaffView session={session} />)}
      </Requires>
    </>
  );
}

// --- A customer's own view ---------------------------------------------------------------------------

function MyVerification({ session }: { session: Session }) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [portfolio, setPortfolio] = useState<any>(null);
  const [result, setResult] = useState<Result | null>(null);
  const id = session.accountId!;
  const load = useCallback(() => {
    get<Detail>(`/v1/customers/${id}`).then(setDetail).catch(() => {});
    get(`/v1/accounts/${id}/portfolio`).then(setPortfolio).catch(() => {});
  }, [id]);
  useEffect(load, [load]);
  usePollWhile(!!detail?.customer.kycReview, load);

  if (!detail) return <div className="muted">Loading…</div>;
  const c = detail.customer;
  async function submit(tier: number) {
    setResult(await write(`/v1/customers/${id}/kyc`, { tier }, newKey("kyc")));
    load();
  }
  return (
    <div className="grid-2">
      <div className="panel">
        <div className="spread"><h2>My verification</h2><KycBadge c={c} /></div>
        <p>{explain(c, detail.tier1LimitKes)}</p>
        <div className="row">
          {c.kycStatus !== "VERIFIED" && !c.kycReview && <button className="primary" onClick={() => submit(1)}>Verify my identity (tier 1)</button>}
          {c.kycStatus === "VERIFIED" && c.kycTier < 2 && !c.kycReview && <button className="primary" onClick={() => submit(2)}>Upgrade to tier 2</button>}
        </div>
        <ResultBanner r={result} />
        <h3 style={{ marginTop: 16 }}>History</h3>
        <Timeline items={detail.history} />
      </div>
      <div className="panel">
        <h2>My portfolio</h2>
        {portfolio?.holdings.length === 0 && <div className="muted">Nothing held yet.</div>}
        <table>
          <tbody>
            {portfolio?.holdings.map((h: any) => (
              <tr key={h.tokenId}><td><b>{h.name}</b><div className="muted mono">{h.tokenId}</div></td>
                <td className="num">{num(h.available)}{h.reserved ? <div className="muted">{num(h.reserved)} reserved</div> : null}</td>
                <td className="num">{kes(h.valueKes)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// --- Staff view ------------------------------------------------------------------------------------------

function StaffView({ session }: { session: Session }) {
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [filter, setFilter] = useState<"all" | "review" | "unverified">("all");
  const [selected, setSelected] = useState<string | null>(null);
  const load = useCallback(() => {
    get<Customer[]>("/v1/customers").then(setCustomers).catch(() => {});
  }, []);
  useEffect(load, [load]);
  usePollWhile(customers.some((c) => c.kycReview === "AWAITING_PROVIDER"), load);

  const shown = customers.filter((c) => !c.accountId.startsWith("bidder")).filter((c) =>
    filter === "review" ? c.kycReview === "NEEDS_REVIEW" : filter === "unverified" ? c.kycStatus !== "VERIFIED" : true);
  const reviewCount = customers.filter((c) => c.kycReview === "NEEDS_REVIEW").length;

  return (
    <div className="grid-main">
      <div className="panel">
        <div className="spread">
          <h2>Customers</h2>
          <div className="row">
            <button className={filter === "all" ? "primary" : ""} onClick={() => setFilter("all")}>All</button>
            <button className={filter === "review" ? "primary" : ""} onClick={() => setFilter("review")}>Needs review ({reviewCount})</button>
            <button className={filter === "unverified" ? "primary" : ""} onClick={() => setFilter("unverified")}>Not verified</button>
          </div>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Customer</th><th>KYC</th><th>Phone</th><th>National ID</th><th>Registered</th><th>Note</th></tr></thead>
            <tbody>
              {shown.map((c) => (
                <tr key={c.accountId} className="clickable" onClick={() => setSelected(c.accountId)}>
                  <td><b>{c.displayName}</b><div className="muted mono">{c.accountId}</div></td>
                  <td><KycBadge c={c} /></td>
                  <td className="mono">{c.piiErased ? <span className="muted">erased</span> : c.phoneMasked ?? <span className="muted">imported</span>}</td>
                  <td className="mono">{c.piiErased ? "" : c.nationalIdMasked ?? ""}</td>
                  <td>{c.registeredAt ? time(c.registeredAt) : <span className="muted">before go-live</span>}</td>
                  <td className="muted" style={{ maxWidth: 260 }}>{c.kycNote}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div>
        {can(session, "customer:register") ? <Register onDone={(id) => { load(); setSelected(id); }} /> : (
          <div className="panel muted">Registering customers needs <code>customer:register</code> (Operations or Platform admin).</div>
        )}
      </div>
      <Drawer open={!!selected} onClose={() => { setSelected(null); load(); }} title={selected ? `Customer ${selected}` : ""}>
        {selected && <CustomerDrawer id={selected} session={session} onChange={load} />}
      </Drawer>
    </div>
  );
}

function Register({ onDone }: { onDone: (accountId: string) => void }) {
  const [f, setF] = useState({ fullName: "", phone: "+2547", nationalId: "", dateOfBirth: "", accountId: "" });
  const [result, setResult] = useState<Result | null>(null);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((x) => ({ ...x, [k]: e.target.value }));
  async function submit() {
    const body: any = { fullName: f.fullName, phone: f.phone, nationalId: f.nationalId };
    if (f.dateOfBirth) body.dateOfBirth = f.dateOfBirth;
    if (f.accountId) body.accountId = f.accountId;
    const r = await write("/v1/customers", body, newKey("register"));
    setResult(r);
    if (r.status === 201) {
      setF({ fullName: "", phone: "+2547", nationalId: "", dateOfBirth: "", accountId: "" });
      onDone(r.body.accountId);
    }
  }
  return (
    <div className="panel">
      <h2>Register a customer</h2>
      <div style={{ display: "grid", gap: 8 }}>
        <label className="field">Full name<input value={f.fullName} onChange={set("fullName")} placeholder="Wanjiku Mwangi" /></label>
        <label className="field">Phone (M-PESA)<input value={f.phone} onChange={set("phone")} /></label>
        <label className="field">National ID<input value={f.nationalId} onChange={set("nationalId")} placeholder="e.g. 28765432" /></label>
        <label className="field">Date of birth<input type="date" value={f.dateOfBirth} onChange={set("dateOfBirth")} /></label>
        <label className="field">Account id (optional)<input value={f.accountId} onChange={set("accountId")} placeholder="generated if empty" /></label>
        <button className="primary" onClick={submit} disabled={!f.fullName || !f.nationalId}>Register</button>
      </div>
      <ResultBanner r={result} />
      <div className="muted" style={{ fontSize: 12, marginTop: 10 }}>
        The demo verification provider rejects ID numbers starting with <code>000</code>, verifies tier 1 otherwise,
        and refers every tier-2 request to a compliance officer.
      </div>
    </div>
  );
}

function CustomerDrawer({ id, session, onChange }: { id: string; session: Session; onChange: () => void }) {
  const [d, setD] = useState<Detail | null>(null);
  const [portfolio, setPortfolio] = useState<any>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [reason, setReason] = useState("");
  const load = useCallback(() => {
    get<Detail>(`/v1/customers/${id}`).then(setD).catch(() => {});
    if (can(session, "token:read")) get(`/v1/accounts/${id}/portfolio`).then(setPortfolio).catch(() => {});
  }, [id, session]);
  useEffect(load, [load]);
  usePollWhile(d?.customer.kycReview === "AWAITING_PROVIDER", load);

  if (!d) return <div className="muted">Loading…</div>;
  const c = d.customer;
  async function act(r: Promise<Result>) {
    setResult(await r);
    load();
    onChange();
  }
  const decide = (decision: string, tier?: number) =>
    act(write(`/v1/customers/${id}/kyc/decision`, { decision, tier, reason: reason || undefined }, newKey("decision")));

  return (
    <>
      <section>
        <div className="spread"><h2 style={{ margin: 0 }}>{c.displayName}</h2><KycBadge c={c} /></div>
        <p>{explain(c, d.tier1LimitKes)}</p>
        <dl className="kv">
          <dt>Account</dt><dd className="mono">{c.accountId}</dd>
          <dt>Phone</dt><dd className="mono">{c.piiErased ? "erased" : c.phoneMasked ?? "not held (imported)"}</dd>
          <dt>National ID</dt><dd className="mono">{c.piiErased ? "erased" : c.nationalIdMasked ?? "not held (imported)"}</dd>
          {c.kycCaseId && <><dt>Open case</dt><dd className="mono">{c.kycCaseId} (tier {c.kycTierRequested})</dd></>}
          {c.kycNote && <><dt>Note</dt><dd>{c.kycNote}</dd></>}
        </dl>
      </section>

      <section>
        <h3>Actions</h3>
        <div className="row">
          {can(session, "kyc:submit") && !c.kycReview && c.kycStatus !== "VERIFIED" && (
            <button className="primary" onClick={() => act(write(`/v1/customers/${id}/kyc`, { tier: 1 }, newKey("kyc")))}>Submit tier-1 KYC</button>
          )}
          {can(session, "kyc:submit") && !c.kycReview && c.kycStatus === "VERIFIED" && c.kycTier < 2 && (
            <button className="primary" onClick={() => act(write(`/v1/customers/${id}/kyc`, { tier: 2 }, newKey("kyc")))}>Request tier 2</button>
          )}
        </div>
        {can(session, "kyc:review") ? (
          <div className="panel" style={{ marginTop: 10, background: "var(--panel-2)", boxShadow: "none" }}>
            <b>Compliance decision</b>
            <label className="field" style={{ marginTop: 6 }}>Reason (required to reject or revoke)
              <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Utility bill and payslips checked" />
            </label>
            <div className="row" style={{ marginTop: 8 }}>
              {c.kycReview && <button className="primary" onClick={() => decide("VERIFY", c.kycTierRequested ?? 1)}>Verify tier {c.kycTierRequested}</button>}
              {c.kycReview && <button className="danger" onClick={() => decide("REJECT")}>Reject</button>}
              {c.kycStatus === "VERIFIED" && <button className="danger" onClick={() => decide("REVOKE")}>Revoke and freeze</button>}
              {!c.piiErased && c.phoneMasked && (
                <button className="ghost" onClick={() => act(call(`/v1/customers/${id}/pii`, { method: "DELETE", key: newKey("erase") }))}>Erase personal data</button>
              )}
            </div>
          </div>
        ) : (
          <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>Deciding cases, revoking and erasing need <code>kyc:review</code> (Compliance officer).</div>
        )}
        <ResultBanner r={result} />
      </section>

      {portfolio && (
        <section>
          <h3>Holdings (derived)</h3>
          {portfolio.holdings.length === 0 && <div className="muted">None.</div>}
          <ul className="mini-list">
            {portfolio.holdings.map((h: any) => (
              <li key={h.tokenId}><span>{h.tokenId}</span><span>{num(h.available)}{h.reserved ? ` · ${num(h.reserved)} reserved` : ""}</span></li>
            ))}
          </ul>
        </section>
      )}

      <section>
        <h3>KYC history (Action Ledger)</h3>
        <Timeline items={d.history} />
      </section>
    </>
  );
}

// --- Shared ----------------------------------------------------------------------------------------------

function explain(c: Customer, limit: number) {
  if (c.kycStatus === "FROZEN") return "Frozen: keeps what it holds, but cannot buy, send or receive until re-verified.";
  if (c.kycReview === "AWAITING_PROVIDER") return `Waiting for the verification provider on a tier-${c.kycTierRequested} check.`;
  if (c.kycReview === "NEEDS_REVIEW") return `The provider referred this tier-${c.kycTierRequested} case to a compliance officer.`;
  if (c.kycStatus === "VERIFIED" && c.kycTier >= 2) return "Verified at tier 2: may buy asset tokens without a per-purchase limit.";
  if (c.kycStatus === "VERIFIED") return `Verified at tier 1: may buy asset tokens up to ${kes(limit)} per purchase.`;
  if (c.kycStatus === "REJECTED") return "Verification was rejected. Loyalty points still work; asset tokens do not.";
  return "Not verified. Loyalty points work; holding asset tokens needs KYC.";
}

function Timeline({ items }: { items: Action[] }) {
  if (!items.length) return <div className="muted">No KYC events.</div>;
  return (
    <ul className="timeline">
      {items.map((a) => (
        <li key={a.id} className={`t-${a.outcome}`}>
          <b>{label(a.eventType)}</b> <span className="muted">#{a.seq} · {time(a.occurredAt)} · {a.actorId}</span>
          {(a.payload.reason || a.payload.tier || a.payload.tierRequested) && (
            <div className="muted">
              {a.payload.tierRequested && `tier ${a.payload.tierRequested} requested. `}
              {a.payload.tier && `tier ${a.payload.tier}. `}
              {a.payload.decidedBy && `Decided by ${a.payload.decidedBy}. `}
              {a.payload.reason}
            </div>
          )}
          {a.payload.piiDigest && <div className="muted mono" style={{ fontSize: 11 }}>data digest {a.payload.piiDigest.slice(0, 16)}…</div>}
        </li>
      ))}
    </ul>
  );
}

function ResultBanner({ r }: { r: Result | null }) {
  if (!r) return null;
  const ok = r.status < 300;
  return (
    <div className={`banner ${ok ? "banner-ok" : "banner-bad"}`}>
      {ok ? <>Done ({r.status}). {r.body?.review === "AWAITING_PROVIDER" ? "The verification provider will answer in a few seconds." : ""}</>
        : <><b>{r.body?.code ?? `HTTP ${r.status}`}</b> {r.body?.detail}</>}
    </div>
  );
}

/** Refreshes every 1.5s while a provider answer is awaited (a customer cannot watch the ledger stream). */
function usePollWhile(active: boolean, load: () => void) {
  useEffect(() => {
    if (!active) return;
    const t = setInterval(load, 1500);
    return () => clearInterval(t);
  }, [active, load]);
}
