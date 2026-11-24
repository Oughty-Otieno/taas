"use client";

import { useCallback, useEffect, useState } from "react";
import { Action, can, get, kes, newKey, num, Result, Session, time, useLive, useSession, write } from "@/lib/api";
import { Drawer, EventTimeline, Requires, ResultNote } from "@/components/ui";

type Business = {
  businessId: string; name: string; registrationNo: string; sector: string | null; payoutAccount: string | null;
  description: string | null; status: "PENDING" | "VERIFIED" | "SUSPENDED"; statusReason: string | null;
  registeredBy: string; registeredAt: string;
};
type Listing = { tokenId: string; name: string; kind: string; assetType: string | null; authorisedSupply: number; issued: number; circulating: number; unitPriceKes: number | null };
type View = { business: Business; tokens: Listing[] };
type Detail = View & { history: Action[] };

const STATUS: Record<string, [string, string]> = {
  PENDING: ["pill-info", "awaiting verification"],
  VERIFIED: ["pill-OK", "verified"],
  SUSPENDED: ["pill-REVERSED", "suspended"],
};

function Status({ b }: { b: Business }) {
  const [cls, text] = STATUS[b.status];
  return <span className={`pill ${cls}`}>{text}</span>;
}

export default function BusinessesPage() {
  const session = useSession();
  return (
    <>
      <h1>Businesses</h1>
      <p className="lede">
        The companies behind the tokens: the issuer of each asset, and the operator of each loyalty programme. An admin
        registers a business; a compliance officer (never the same person) verifies it before any asset is listed under
        it. A suspended business cannot issue or sell new units, but its holders keep theirs.
      </p>
      <Requires anyOf={["token:read"]} what="Businesses are visible to every staff role.">
        {session && <Directory session={session} />}
      </Requires>
    </>
  );
}

function Directory({ session }: { session: Session }) {
  const { data, reload } = useLive<View[]>("/v1/businesses");
  const [selected, setSelected] = useState<string | null>(null);
  const list = data ?? [];
  const pending = list.filter((v) => v.business.status === "PENDING").length;

  return (
    <div className="grid-main">
      <div className="panel">
        <div className="spread">
          <h2>Directory</h2>
          {pending > 0 && <span className="pill pill-info">{pending} awaiting verification</span>}
        </div>
        {data && list.length === 0 && <div className="muted">No businesses yet. Load the example data, or register one.</div>}
        <div className="table-wrap">
          <table>
            <thead><tr><th>Business</th><th>Status</th><th>Sector</th><th>Tokens</th><th>Registered</th></tr></thead>
            <tbody>
              {list.map(({ business: b, tokens }) => (
                <tr key={b.businessId} className="clickable" onClick={() => setSelected(b.businessId)}>
                  <td><b>{b.name}</b><div className="muted mono">{b.registrationNo}</div></td>
                  <td><Status b={b} /></td>
                  <td>{b.sector}</td>
                  <td>{tokens.length === 0 ? <span className="muted">none yet</span> : tokens.map((t) => <div key={t.tokenId} className="mono">{t.tokenId}</div>)}</td>
                  <td>{time(b.registeredAt)}<div className="muted">by {b.registeredBy}</div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div>
        {can(session, "business:manage") ? <Register onDone={(id) => { reload(); setSelected(id); }} /> : (
          <div className="panel muted">Registering businesses needs <code>business:manage</code> (Platform admin).</div>
        )}
      </div>
      <Drawer open={!!selected} onClose={() => { setSelected(null); reload(); }} title={selected ? "Business" : ""}>
        {selected && <BusinessDrawer id={selected} session={session} onChange={reload} />}
      </Drawer>
    </div>
  );
}

function Register({ onDone }: { onDone: (id: string) => void }) {
  const empty = { name: "", registrationNo: "", sector: "", payoutAccount: "", description: "" };
  const [f, setF] = useState(empty);
  const [result, setResult] = useState<Result | null>(null);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((x) => ({ ...x, [k]: e.target.value }));
  async function submit() {
    const body: any = { name: f.name, registrationNo: f.registrationNo };
    for (const k of ["sector", "payoutAccount", "description"] as const) if (f[k]) body[k] = f[k];
    const r = await write("/v1/businesses", body, newKey("business"));
    setResult(r);
    if (r.status === 201) {
      setF(empty);
      onDone(r.body.businessId);
    }
  }
  return (
    <div className="panel">
      <h2>Register a business</h2>
      <div className="form-stack">
        <label className="field">Registered name<input value={f.name} onChange={set("name")} placeholder="Riverside Properties Ltd" /></label>
        <label className="field">Registration number<input value={f.registrationNo} onChange={set("registrationNo")} placeholder="PVT-2016-11873" /></label>
        <label className="field">Sector<input value={f.sector} onChange={set("sector")} placeholder="Real estate" /></label>
        <label className="field">Payout account<input value={f.payoutAccount} onChange={set("payoutAccount")} placeholder="Paybill 522011" /></label>
        <label className="field">Description<input value={f.description} onChange={set("description")} placeholder="What the business does" /></label>
        <button className="primary" onClick={submit} disabled={!f.name || !f.registrationNo}>Register</button>
      </div>
      <ResultNote r={result} ok="It now awaits verification by a compliance officer." />
      <div className="muted" style={{ fontSize: 12, marginTop: 10 }}>
        Only public company details are recorded here; they appear in the Action Ledger.
      </div>
    </div>
  );
}

function BusinessDrawer({ id, session, onChange }: { id: string; session: Session; onChange: () => void }) {
  const [d, setD] = useState<Detail | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [reason, setReason] = useState("");
  const load = useCallback(() => { get<Detail>(`/v1/businesses/${id}`).then(setD).catch(() => {}); }, [id]);
  useEffect(load, [load]);

  if (!d) return <div className="muted">Loading…</div>;
  const b = d.business;
  async function act(r: Promise<Result>) {
    setResult(await r);
    load();
    onChange();
  }
  const decide = (decision: string) =>
    act(write(`/v1/businesses/${id}/decision`, { decision, reason: reason || undefined }, newKey("kyb")));
  const ownRegistration = b.registeredBy === session.subject;

  return (
    <>
      <section>
        <div className="spread"><h2 style={{ margin: 0 }}>{b.name}</h2><Status b={b} /></div>
        <p>{b.description}</p>
        <dl className="kv">
          <dt>Id</dt><dd className="mono">{b.businessId}</dd>
          <dt>Registration no.</dt><dd className="mono">{b.registrationNo}</dd>
          {b.sector && <><dt>Sector</dt><dd>{b.sector}</dd></>}
          {b.payoutAccount && <><dt>Payout account</dt><dd>{b.payoutAccount}</dd></>}
          <dt>Registered</dt><dd>{time(b.registeredAt)} by {b.registeredBy}</dd>
          {b.statusReason && <><dt>Last decision</dt><dd>{b.statusReason}</dd></>}
        </dl>
      </section>

      <section>
        <h3>Verification</h3>
        {can(session, "business:review") ? (
          <>
            <label className="field">Reason (required to suspend)
              <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Certificate of incorporation and directors checked" />
            </label>
            <div className="row" style={{ marginTop: 8 }}>
              {b.status !== "VERIFIED" && <button className="primary" onClick={() => decide("VERIFY")}>{b.status === "SUSPENDED" ? "Reinstate" : "Verify"}</button>}
              {b.status === "VERIFIED" && <button className="danger" onClick={() => decide("SUSPEND")}>Suspend</button>}
            </div>
            {ownRegistration && b.status === "PENDING" && (
              <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>You registered this business, so another officer must verify it.</div>
            )}
          </>
        ) : (
          <div className="muted" style={{ fontSize: 12 }}>Verifying and suspending need <code>business:review</code> (Compliance officer).</div>
        )}
        <ResultNote r={result} />
      </section>

      <section>
        <h3>Tokens issued by {b.name}</h3>
        {d.tokens.length === 0 && <div className="muted">None yet.</div>}
        {d.tokens.length > 0 && (
          <table>
            <thead><tr><th>Token</th><th className="num">Issued / authorised</th><th className="num">In circulation</th><th className="num">Unit price</th></tr></thead>
            <tbody>
              {d.tokens.map((t) => (
                <tr key={t.tokenId}>
                  <td><b>{t.name}</b><div className="muted mono">{t.tokenId}{t.assetType ? ` · ${t.assetType}` : ""}</div></td>
                  <td className="num">{num(t.issued)} / {num(t.authorisedSupply)}</td>
                  <td className="num">{num(t.circulating)}</td>
                  <td className="num">{t.unitPriceKes ? kes(t.unitPriceKes) : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {b.status === "VERIFIED" && can(session, "token:issue") && <AddAsset business={b} onDone={() => { load(); onChange(); }} />}
        {b.status === "VERIFIED" && can(session, "business:manage") && <AssignExisting business={b} onDone={() => { load(); onChange(); }} />}
        {b.status !== "VERIFIED" && <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>Assets can be listed once the business is verified.</div>}
      </section>

      <section>
        <h3>History (Action Ledger)</h3>
        <EventTimeline items={d.history} />
      </section>
    </>
  );
}

/** Defines an asset token issued by this business, and optionally mints its supply into the treasury. */
function AddAsset({ business, onDone }: { business: Business; onDone: () => void }) {
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ tokenId: "RWA_", name: "", assetType: business.sector ?? "", backing: "", valuationKes: "", unitPriceKes: "", authorisedSupply: "", maxHolderPercent: "20", issueNow: true });
  const [results, setResults] = useState<Result[]>([]);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((x) => ({ ...x, [k]: e.target.value }));
  const n = (v: string) => (v ? Number(v) : undefined);

  // Valuation = units × price per unit. The two figures typed most recently are kept; the third is calculated.
  type Money = "valuationKes" | "authorisedSupply" | "unitPriceKes";
  const [typed, setTyped] = useState<Money[]>(["authorisedSupply", "unitPriceKes"]);
  const calculated = (["valuationKes", "authorisedSupply", "unitPriceKes"] as Money[]).find((k) => !typed.includes(k))!;
  const setMoney = (k: Money) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setF((x) => ({ ...x, [k]: e.target.value }));
    if (k !== typed[1]) setTyped([typed[1], k]);
  };
  const a = n(f[typed[0]]), b = n(f[typed[1]]);
  let derived: number | undefined;
  let mismatch = "";
  if (a && b) {
    if (calculated === "valuationKes") derived = a * b;
    else {
      const total = typed[0] === "valuationKes" ? a : b, other = typed[0] === "valuationKes" ? b : a;
      derived = total / other;
      if (!Number.isInteger(derived)) {
        mismatch = calculated === "unitPriceKes"
          ? `KES ${num(total)} does not divide evenly into ${num(other)} units. Change the units or the valuation.`
          : `KES ${num(total)} at KES ${num(other)} per unit is not a whole number of units. Change the price or the valuation.`;
        derived = undefined;
      }
    }
  }
  const figures = { ...f, [calculated]: derived === undefined ? "" : String(derived) };
  const price = n(figures.unitPriceKes), supply = n(figures.authorisedSupply), valuation = n(figures.valuationKes);
  const moneyField = (k: Money, label: string) => (
    <label className="field"><span>{label}{k === calculated && <span className="muted" style={{ fontWeight: 400 }}> · calculated</span>}</span>
      <input type="number" min={1} step={1} value={figures[k]} onChange={setMoney(k)} readOnly={k === calculated}
        style={k === calculated ? { background: "var(--panel-2)" } : undefined}
        title={k === calculated ? "Calculated from the other two. Type in this field to calculate a different one instead." : undefined} />
    </label>
  );

  async function submit() {
    const define = await write("/v1/tokens", {
      tokenId: f.tokenId, name: f.name, kind: "ASSET", assetType: f.assetType || undefined, backing: f.backing || undefined,
      valuationKes: valuation, unitPriceKes: price, authorisedSupply: supply,
      maxHolderPercent: n(f.maxHolderPercent), issuerId: business.businessId,
    }, newKey("define"));
    const out = [define];
    if (define.status === 201 && f.issueNow && supply) {
      out.push(await write("/v1/issuances", { tokenId: f.tokenId, units: supply, reference: `Tokenisation by ${business.name}` }, newKey("issue")));
    }
    setResults(out);
    if (define.status === 201) onDone();
  }

  if (!open) return <button className="primary" style={{ marginTop: 10 }} onClick={() => setOpen(true)}>Add an asset</button>;
  return (
    <div className="panel" style={{ marginTop: 10, background: "var(--panel-2)", boxShadow: "none" }}>
      <b>New asset issued by {business.name}</b>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, marginTop: 8 }}>
        <label className="field">Token id<input value={f.tokenId} onChange={(e) => setF((x) => ({ ...x, tokenId: e.target.value.toUpperCase() }))} placeholder="RWA_APT002" /></label>
        <label className="field">Name<input value={f.name} onChange={set("name")} placeholder="Riverside Apartment 002" /></label>
        <label className="field">Asset type<input value={f.assetType} onChange={set("assetType")} placeholder="Apartment" /></label>
        {moneyField("valuationKes", "Valuation (KES)")}
        {moneyField("authorisedSupply", "Units (authorised supply)")}
        {moneyField("unitPriceKes", "Price per unit (KES)")}
        <label className="field">Ownership cap (% per holder)<input type="number" value={f.maxHolderPercent} onChange={set("maxHolderPercent")} /></label>
        <label className="field">What backs it<input value={f.backing} onChange={set("backing")} placeholder="Title held by a trustee" /></label>
      </div>
      <label className="row" style={{ marginTop: 8, gap: 6 }}>
        <input type="checkbox" checked={f.issueNow} onChange={(e) => setF((x) => ({ ...x, issueNow: e.target.checked }))} />
        Issue the full supply into the treasury now
      </label>
      <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>Enter any two of valuation, units and price per unit; the third is calculated.</div>
      {mismatch && <div className="banner banner-bad" style={{ marginTop: 6 }}>{mismatch}</div>}
      {price && supply ? <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>{num(supply)} units at {kes(price)} = {kes(price * supply)} in total; one holder may own at most {num(Math.floor(supply * Number(f.maxHolderPercent || 100) / 100))} units.</div> : null}
      <div className="row" style={{ marginTop: 8 }}>
        <button className="primary" onClick={submit} disabled={!f.tokenId || !f.name || !price || !supply}>Create asset</button>
        <button className="ghost" onClick={() => setOpen(false)}>Cancel</button>
      </div>
      {results.map((r, i) => <ResultNote key={i} r={r} ok={i === 0 ? "Asset defined." : "Supply issued into the treasury."} />)}
    </div>
  );
}

/** Names this business as the issuer of a token that has none (tokens defined before businesses were recorded). */
function AssignExisting({ business, onDone }: { business: Business; onDone: () => void }) {
  const [tokens, setTokens] = useState<{ token: { tokenId: string; name: string }; issuer: Business | null }[]>([]);
  const [choice, setChoice] = useState("");
  const [result, setResult] = useState<Result | null>(null);
  useEffect(() => { get<any[]>("/v1/tokens").then((t) => setTokens(t.filter((x) => !x.issuer))).catch(() => {}); }, [result]);
  if (tokens.length === 0) return null;
  return (
    <div className="row" style={{ marginTop: 10 }}>
      <select value={choice} onChange={(e) => setChoice(e.target.value)}>
        <option value="">Token with no issuer…</option>
        {tokens.map((t) => <option key={t.token.tokenId} value={t.token.tokenId}>{t.token.name} ({t.token.tokenId})</option>)}
      </select>
      <button disabled={!choice} onClick={async () => {
        const r = await write(`/v1/tokens/${choice}/issuer`, { issuerId: business.businessId }, newKey("assign"));
        setResult(r);
        if (r.status === 200) { setChoice(""); onDone(); }
      }}>Name {business.name} as issuer</button>
      <ResultNote r={result} />
    </div>
  );
}
