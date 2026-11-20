"use client";

import { useCallback, useEffect, useState } from "react";
import { Action, call, get, label, num, time, useStream, who } from "@/lib/api";
import { Check, Drawer, Hash, Json, Outcome, Requires } from "@/components/ui";

const EVENT_TYPES = [
  "TOKEN_DEFINED", "TOKEN_ISSUED", "TOKEN_EARNED", "AWARD_NOT_QUALIFIED", "TOKEN_RESERVED", "TOKEN_RESERVATION_RELEASED",
  "TOKEN_RESERVATION_EXPIRED", "ORDER_PLACED", "PAYMENT_RECEIVED", "PAYMENT_FAILED", "PAYMENT_REFUND_REQUIRED",
  "TOKEN_PURCHASED", "TOKEN_TRANSFERRED", "TOKEN_REDEEMED", "TOKEN_REVOKED", "RULE_VERSION_PUBLISHED", "REQUEST_REJECTED",
];

type Filters = { tokenId: string; account: string; assetType: string; type: string; from: string; to: string };
const EMPTY: Filters = { tokenId: "", account: "", assetType: "", type: "", from: "", to: "" };

export default function LedgerPage() {
  return (
    <Requires anyOf={["ledger:read"]} what="The Action Ledger is for staff and auditors.">
      <LedgerExplorer />
    </Requires>
  );
}

function LedgerExplorer() {
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [items, setItems] = useState<Action[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [tokens, setTokens] = useState<any[]>([]);
  const [selected, setSelected] = useState<Action | null>(null);
  const [verify, setVerify] = useState<any>(null);
  const [replay, setReplay] = useState<any>(null);
  const [wipe, setWipe] = useState<any>(null);
  const [busy, setBusy] = useState("");

  const query = useCallback(
    (c?: number | null) => {
      const q = new URLSearchParams({ limit: "50" });
      Object.entries(filters).forEach(([k, v]) => {
        if (!v) return;
        q.set(k, k === "from" || k === "to" ? new Date(v).toISOString() : v);
      });
      if (c) q.set("cursor", String(c));
      return `/v1/ledger/actions?${q}`;
    },
    [filters],
  );

  const load = useCallback(() => {
    get<{ items: Action[]; nextCursor: number | null }>(query()).then((p) => {
      setItems(p.items);
      setCursor(p.nextCursor);
    });
  }, [query]);

  useEffect(load, [load]);
  useEffect(() => {
    get<any[]>("/v1/tokens").then(setTokens).catch(() => {});
  }, []);

  const filtered = Object.values(filters).some(Boolean);
  useStream((a) => {
    if (filtered) return;
    setItems((prev) => (prev.some((p) => p.id === a.id) ? prev : [a, ...prev]));
  });

  async function more() {
    if (!cursor) return;
    const p = await get<{ items: Action[]; nextCursor: number | null }>(query(cursor));
    setItems((prev) => [...prev, ...p.items]);
    setCursor(p.nextCursor);
  }

  async function run(name: string, fn: () => Promise<void>) {
    setBusy(name);
    try {
      await fn();
    } finally {
      setBusy("");
    }
  }

  const assetTypes = Array.from(new Set(tokens.map((t) => t.token.assetType ?? "LOYALTY")));
  const set = (k: keyof Filters) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setFilters((f) => ({ ...f, [k]: e.target.value }));

  return (
    <>
      <h1>Action Ledger Explorer</h1>
      <p className="lede">
        Everything that ever happened, append-only and hash-chained. Every movement has a reason: click a row to see
        the raw event, the movements it wrote, the workflow decision behind it and the transaction it belongs to.
      </p>

      <div className="grid-2" style={{ marginBottom: 14 }}>
        <div className="panel">
          <h2>Integrity</h2>
          <div className="row">
            <button className="primary" disabled={!!busy} onClick={() => run("verify", async () => setVerify(await get("/v1/ledger/verify")))}>
              {busy === "verify" ? "Verifying…" : "Verify chain"}
            </button>
            {verify && (
              <span className="row">
                <Check ok={verify.hashChainValid}>{num(verify.events)} hashes chain</Check>
                <Check ok={verify.holdingsMatchMovements}>holdings = Σ movements</Check>
                <Check ok={verify.unbalancedActions.length === 0}>every action sums to zero</Check>
              </span>
            )}
          </div>
          {verify && !verify.hashChainValid && <div className="banner banner-bad">History was altered at seq {verify.firstBrokenSeq}.</div>}
          {verify && <div className="muted" style={{ marginTop: 8, fontSize: 12 }}>Head hash <Hash value={verify.headHash} /></div>}
        </div>
        <div className="panel">
          <h2>Replay test</h2>
          <div className="row">
            <button className="danger" disabled={!!busy} onClick={() => run("wipe", async () => {
              const r = await call("/v1/ledger/wipe", { method: "POST" });
              setWipe(r.body);
              setReplay(null);
            })}>1 · Delete balance tables</button>
            <button className="primary" disabled={!!busy} onClick={() => run("replay", async () => {
              const r = await call("/v1/ledger/replay", { method: "POST" });
              setReplay(r.body);
              setWipe(null);
            })}>2 · Replay the Action Ledger</button>
          </div>
          {wipe && (
            <div className="banner banner-bad">
              Deleted every movement, holding, reservation and order ({Object.keys(wipe.holdingsBefore).length} holdings).
              The Action Ledger still has {num(wipe.eventsInActionLedger)} events. Balances now read zero.
            </div>
          )}
          {replay?.title && <div className="banner banner-bad">{replay.detail}</div>}
          {replay?.eventsReplayed != null && (
            <div className={`banner ${replay.holdingsIdentical && replay.workflowIdentical ? "banner-ok" : "banner-bad"}`}>
              Replayed {num(replay.eventsReplayed)} events into {num(replay.movementsRebuilt)} movements.{" "}
              {replay.holdingsIdentical && replay.workflowIdentical ? "Same balances as before." : "Balances differ!"}
              <div style={{ fontSize: 12, marginTop: 4 }}>Compared with {replay.comparedWith}.</div>
            </div>
          )}
          {replay?.holdingsAfter && <ReplayTable report={replay} />}
        </div>
      </div>

      <div className="panel">
        <div className="row" style={{ marginBottom: 10 }}>
          <label className="field">Token
            <select value={filters.tokenId} onChange={set("tokenId")}>
              <option value="">All</option>
              {tokens.map((t) => <option key={t.token.tokenId}>{t.token.tokenId}</option>)}
            </select>
          </label>
          <label className="field">User<input value={filters.account} onChange={set("account")} placeholder="e.g. john" size={10} /></label>
          <label className="field">Asset type
            <select value={filters.assetType} onChange={set("assetType")}>
              <option value="">All</option>
              {assetTypes.map((a) => <option key={a}>{a}</option>)}
            </select>
          </label>
          <label className="field">Event type
            <select value={filters.type} onChange={set("type")}>
              <option value="">All</option>
              {EVENT_TYPES.map((t) => <option key={t}>{t}</option>)}
            </select>
          </label>
          <label className="field">From<input type="datetime-local" value={filters.from} onChange={set("from")} /></label>
          <label className="field">To<input type="datetime-local" value={filters.to} onChange={set("to")} /></label>
          <button className="ghost" onClick={() => setFilters(EMPTY)} style={{ alignSelf: "flex-end" }}>Clear</button>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>#</th><th>Timestamp</th><th>Event type</th><th>User</th><th>Token</th><th>Rule used</th>
                <th>Idempotency key</th><th>Event hash</th><th>Status</th>
              </tr>
            </thead>
            <tbody>
              {items.map((a) => (
                <tr key={a.id} className={`clickable row-${a.outcome}`} onClick={() => setSelected(a)}>
                  <td className="mono">{a.seq}</td>
                  <td>{time(a.occurredAt)}</td>
                  <td><b>{a.eventType}</b></td>
                  <td>{who(a)}</td>
                  <td>{a.tokenId ?? "—"}</td>
                  <td className="mono">{a.ruleVersion}</td>
                  <td className="mono">{a.idempotencyKey.length > 26 ? a.idempotencyKey.slice(0, 26) + "…" : a.idempotencyKey}</td>
                  <td><Hash value={a.hash} /></td>
                  <td><Outcome outcome={a.outcome} code={a.payload.code} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {cursor && <div style={{ marginTop: 10 }}><button onClick={more}>Load older</button></div>}
      </div>

      <Drawer open={!!selected} onClose={() => setSelected(null)} title={selected ? `#${selected.seq} ${selected.eventType}` : ""}>
        {selected && <EventDetail a={selected} />}
      </Drawer>
    </>
  );
}

function ReplayTable({ report }: { report: any }) {
  const keys = Array.from(new Set([...Object.keys(report.holdingsBefore), ...Object.keys(report.holdingsAfter)]))
    .filter((k) => !k.split("|")[1].startsWith("@")).sort().slice(0, 12);
  return (
    <div className="table-wrap" style={{ marginTop: 8 }}>
      <table>
        <thead><tr><th>Token · account · bucket</th><th className="num">Before</th><th className="num">After replay</th><th></th></tr></thead>
        <tbody>
          {keys.map((k) => (
            <tr key={k}>
              <td className="mono">{k.replaceAll("|", " · ")}</td>
              <td className="num">{num(report.holdingsBefore[k] ?? 0)}</td>
              <td className="num">{num(report.holdingsAfter[k] ?? 0)}</td>
              <td><Check ok={report.holdingsBefore[k] === report.holdingsAfter[k]}>{""}</Check></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EventDetail({ a }: { a: Action }) {
  const [movements, setMovements] = useState<any[] | null>(null);
  const [balances, setBalances] = useState<any[]>([]);
  const [related, setRelated] = useState<Action[]>([]);
  const [proof, setProof] = useState<any>(null);

  useEffect(() => {
    setProof(null);
    get<any[]>(`/v1/ledger/movements?actionId=${a.id}`).then(async (m) => {
      setMovements(m);
      const accounts = Array.from(new Set(m.map((x) => x.accountId).filter((x: string) => !x.startsWith("@"))));
      const portfolios = await Promise.all(accounts.map((id) => get(`/v1/accounts/${id}/portfolio`)));
      setBalances(portfolios);
    });
    const link = a.payload.orderId ?? a.payload.reservationId;
    if (link) get<{ items: Action[] }>(`/v1/ledger/actions?related=${link}&order=oldest`).then((p) => setRelated(p.items));
    else setRelated([]);
  }, [a]);

  const p = a.payload;
  return (
    <>
      <section>
        <h3>Workflow decision</h3>
        <dl className="kv">
          <dt>Outcome</dt><dd><Outcome outcome={a.outcome} code={p.code} /></dd>
          <dt>Decided by</dt><dd className="mono">{a.ruleVersion}</dd>
          {p.matched !== undefined && <><dt>Rules matched</dt><dd className="mono">{p.matched || "none"}</dd></>}
          {p.reason && <><dt>Reason</dt><dd>{p.reason}</dd></>}
          <dt>Actor</dt><dd>{a.actorId}</dd>
          <dt>Idempotency key</dt><dd className="mono">{a.idempotencyKey}</dd>
          <dt>Time</dt><dd>{new Date(a.occurredAt).toISOString()}</dd>
        </dl>
      </section>
      <section>
        <h3>Ownership movement</h3>
        {movements?.length === 0 && <div className="muted">No units moved: this event records a decision.</div>}
        {movements && movements.length > 0 && (
          <table>
            <thead><tr><th>Account</th><th>Bucket</th><th className="num">Units</th></tr></thead>
            <tbody>
              {movements.map((m) => (
                <tr key={m.id}><td>{m.accountId}</td><td>{m.bucket}</td>
                  <td className={`num ${m.units > 0 ? "delta-up" : "delta-down"}`}>{m.units > 0 ? "+" : ""}{num(m.units)}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {balances.length > 0 && (
        <section>
          <h3>Affected balances (now, derived)</h3>
          {balances.map((b) => (
            <div key={b.accountId} style={{ marginBottom: 6 }}>
              <b>{b.displayName}</b> <span className="muted">({b.accountId}, KYC {b.kycStatus})</span>
              <ul className="mini-list">
                {b.holdings.map((h: any) => (
                  <li key={h.tokenId}><span>{h.tokenId}</span><span>{num(h.available)} available{h.reserved ? ` · ${num(h.reserved)} reserved` : ""}</span></li>
                ))}
              </ul>
            </div>
          ))}
        </section>
      )}
      {related.length > 1 && (
        <section>
          <h3>Related transaction</h3>
          <ul className="mini-list">
            {related.map((r) => (
              <li key={r.id}><span><Outcome outcome={r.outcome} code={label(r.eventType)} /></span><span className="muted">#{r.seq} · {time(r.occurredAt)}</span></li>
            ))}
          </ul>
        </section>
      )}
      <section>
        <h3>Chain</h3>
        <dl className="kv">
          <dt>Previous hash</dt><dd className="mono">{a.prevHash}</dd>
          <dt>Hash</dt><dd className="mono">{a.hash}</dd>
        </dl>
        <div className="row" style={{ marginTop: 8 }}>
          <button onClick={() => get(`/v1/ledger/actions/${a.id}/proof`).then(setProof)}>Prove inclusion</button>
          {proof && (
            <>
              <Check ok={proof.eventHashValid}>hash recomputes</Check>
              <Check ok={proof.chainValid}>{proof.chainToHead.length} links to head #{proof.headSeq}</Check>
            </>
          )}
        </div>
      </section>
      <section>
        <h3>Raw event</h3>
        <Json value={a} />
      </section>
    </>
  );
}
