"use client";

import { useEffect, useState } from "react";
import { get, kes, newKey, num, Result, time, useLive, write } from "@/lib/api";
import { EventTape } from "@/components/ui";

type Token = { token: { tokenId: string; name: string; kind: "LOYALTY" | "ASSET"; unitPriceKes: number | null }; supply: any };
type Account = { accountId: string; displayName: string; kycStatus: string };
type Sent = { op: string; path: string; body: unknown; method: string; result: Result };

/**
 * The token operations, one form each, for loyalty and asset tokens alike. Every submit sends a fresh
 * Idempotency-Key; "Send again" repeats the last request with the same key to show it is not applied twice.
 */
export default function Operations() {
  const { data: tokens } = useLive<Token[]>("/v1/tokens");
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [last, setLast] = useState<Sent | null>(null);

  useEffect(() => {
    get<Account[]>("/v1/accounts").then((a) => setAccounts(a.filter((x) => !x.accountId.startsWith("bidder")))).catch(() => {});
  }, []);

  async function send(op: string, path: string, body: unknown, method = "POST") {
    const result = await write(path, body, newKey(op.toLowerCase()), method);
    setLast({ op, path, body, method, result });
    return result;
  }

  async function again() {
    if (!last) return;
    const result = await write(last.path, last.body, last.result.key, last.method);
    setLast({ ...last, result });
  }

  const all = tokens ?? [];
  const assets = all.filter((t) => t.token.kind === "ASSET");
  const loyalty = all.filter((t) => t.token.kind === "LOYALTY");

  return (
    <>
      <h1>Token operations</h1>
      <p className="lede">
        Issue, earn, reserve, transfer and redeem, for loyalty and asset tokens alike: the same operations, the same
        engine, the same ledgers. Each request carries a new Idempotency-Key; use <b>Send again</b> to retry the last
        one with the same key and see that nothing moves twice.
      </p>
      {all.length === 0 && <div className="banner banner-info">No tokens yet. Load the example data from the Command Centre.</div>}

      <div className="grid-main">
        <div>
          <div className="grid-2">
            <Issue tokens={all} send={send} />
            <Earn accounts={accounts} loyalty={loyalty} send={send} />
            <Transfer tokens={all} accounts={accounts} send={send} />
            <Redeem tokens={all} accounts={accounts} send={send} />
          </div>
          <Reserve assets={assets} accounts={accounts} send={send} />
        </div>
        <div>
          <LastResult last={last} again={again} />
          <div style={{ marginTop: 14 }}><EventTape limit={20} /></div>
        </div>
      </div>
    </>
  );
}

type SendFn = (op: string, path: string, body: unknown, method?: string) => Promise<Result>;

function TokenSelect({ tokens, value, onChange }: { tokens: Token[]; value: string; onChange: (v: string) => void }) {
  useEffect(() => {
    if (!value && tokens.length) onChange(tokens[0].token.tokenId);
  }, [tokens, value, onChange]);
  return (
    <label className="field">Token
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {tokens.map((t) => <option key={t.token.tokenId} value={t.token.tokenId}>{t.token.tokenId} · {t.token.name}</option>)}
      </select>
    </label>
  );
}

/**
 * Picks an account from those this sign-in may see. A customer sees only their own, so a default such as "alice"
 * is replaced by the first account listed: the form must send what the menu shows. With {@code free}, a customer
 * (who cannot list other people's accounts) types the account instead, as for the receiver of a transfer.
 */
function AccountSelect({ label, accounts, value, onChange, free = false }: { label: string; accounts: Account[]; value: string; onChange: (v: string) => void; free?: boolean }) {
  const typed = free && accounts.length <= 1;
  useEffect(() => {
    if (!typed && accounts.length && !accounts.some((a) => a.accountId === value)) onChange(accounts[0].accountId);
  }, [accounts, value, onChange, typed]);
  if (typed) {
    return <label className="field">{label}<input value={value} onChange={(e) => onChange(e.target.value.trim())} placeholder="account id, e.g. alice" style={{ width: 150 }} /></label>;
  }
  return (
    <label className="field">{label}
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {accounts.map((a) => <option key={a.accountId} value={a.accountId}>{a.displayName}{a.kycStatus !== "VERIFIED" ? " (KYC pending)" : ""}</option>)}
      </select>
    </label>
  );
}

function Units({ value, onChange, label = "Units" }: { value: number; onChange: (v: number) => void; label?: string }) {
  return <label className="field">{label}<input type="number" min={1} value={value} onChange={(e) => onChange(Number(e.target.value))} style={{ width: 110 }} /></label>;
}

function Issue({ tokens, send }: { tokens: Token[]; send: SendFn }) {
  const [tokenId, setTokenId] = useState("");
  const [units, setUnits] = useState(1000);
  return (
    <div className="panel">
      <h2>Issue</h2>
      <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>Mint into the treasury (admin, scope token:issue). The authorised supply is a database ceiling.</div>
      <div className="row">
        <TokenSelect tokens={tokens} value={tokenId} onChange={setTokenId} />
        <Units value={units} onChange={setUnits} />
        <button className="primary" style={{ alignSelf: "flex-end" }} disabled={!tokenId}
          onClick={() => send("Issue", "/v1/issuances", { tokenId, units, reference: "Console issuance" })}>Issue</button>
      </div>
    </div>
  );
}

function Earn({ accounts, loyalty, send }: { accounts: Account[]; loyalty: Token[]; send: SendFn }) {
  const [account, setAccount] = useState("john");
  const [amount, setAmount] = useState(1200);
  return (
    <div className="panel">
      <h2>Earn <span className="pill pill-OK">loyalty</span></h2>
      <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
        Submit a settled transaction; the active rule set decides the units{loyalty[0] ? ` of ${loyalty[0].token.tokenId}` : ""}. The client never names an amount.
      </div>
      <div className="row">
        <AccountSelect label="Customer" accounts={accounts} value={account} onChange={setAccount} />
        <Units label="Spend (KES)" value={amount} onChange={setAmount} />
        <button className="primary" style={{ alignSelf: "flex-end" }}
          onClick={() => send("Earn", "/v1/awards", { accountId: account, transaction: { type: "TXN_SETTLED", amountKes: amount, merchantId: "console", channel: "POS", reference: `TXN-${Date.now()}` } })}>Earn</button>
      </div>
    </div>
  );
}

function Transfer({ tokens, accounts, send }: { tokens: Token[]; accounts: Account[]; send: SendFn }) {
  const [tokenId, setTokenId] = useState("");
  const [from, setFrom] = useState("john");
  const [to, setTo] = useState("alice");
  const [units, setUnits] = useState(20);
  return (
    <div className="panel">
      <h2>Transfer</h2>
      <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>Holder to holder. Overdraft and the 20% cap are database checks; KYC and velocity are policy decisions.</div>
      <div className="row">
        <TokenSelect tokens={tokens} value={tokenId} onChange={setTokenId} />
        <AccountSelect label="From" accounts={accounts} value={from} onChange={setFrom} />
        <AccountSelect label="To" accounts={accounts} value={to} onChange={setTo} free />
        <Units value={units} onChange={setUnits} />
        <button className="primary" style={{ alignSelf: "flex-end" }} disabled={!tokenId}
          onClick={() => send("Transfer", "/v1/transfers", { tokenId, fromAccountId: from, toAccountId: to, units, reference: "Console transfer" })}>Transfer</button>
      </div>
    </div>
  );
}

function Redeem({ tokens, accounts, send }: { tokens: Token[]; accounts: Account[]; send: SendFn }) {
  const [tokenId, setTokenId] = useState("");
  const [account, setAccount] = useState("john");
  const [units, setUnits] = useState(10);
  return (
    <div className="panel">
      <h2>Redeem</h2>
      <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>Burn units against a merchant (loyalty) or a payout (asset). Units move to @burn.</div>
      <div className="row">
        <TokenSelect tokens={tokens} value={tokenId} onChange={setTokenId} />
        <AccountSelect label="Holder" accounts={accounts} value={account} onChange={setAccount} />
        <Units value={units} onChange={setUnits} />
        <button className="primary" style={{ alignSelf: "flex-end" }} disabled={!tokenId}
          onClick={() => send("Redeem", "/v1/redemptions", { tokenId, accountId: account, units, merchantId: "console", reference: "Console redemption" })}>Redeem</button>
      </div>
    </div>
  );
}

function Reserve({ assets, accounts, send }: { assets: Token[]; accounts: Account[]; send: SendFn }) {
  const [tokenId, setTokenId] = useState("");
  const [account, setAccount] = useState("alice");
  const [units, setUnits] = useState(250);
  const [hold, setHold] = useState(120);
  const { data: open, reload } = useLive<any[]>(tokenId ? `/v1/reservations?tokenId=${tokenId}&status=HELD&limit=20` : null);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const price = assets.find((a) => a.token.tokenId === tokenId)?.token.unitPriceKes;

  return (
    <div className="panel" style={{ marginTop: 16 }}>
      <h2>Reserve <span className="pill pill-info">asset</span></h2>
      <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
        Hold units for a bid. They move from @treasury into the buyer&apos;s RESERVED bucket (counted toward the cap) until
        the order is paid, the bid is released, or the hold runs out.
      </div>
      <div className="row">
        <TokenSelect tokens={assets} value={tokenId} onChange={setTokenId} />
        <AccountSelect label="Buyer" accounts={accounts} value={account} onChange={setAccount} />
        <Units value={units} onChange={setUnits} />
        <label className="field">Hold (s)<input type="number" min={10} value={hold} onChange={(e) => setHold(Number(e.target.value))} style={{ width: 80 }} /></label>
        <button className="primary" style={{ alignSelf: "flex-end" }} disabled={!tokenId}
          onClick={async () => { await send("Reserve", "/v1/reservations", { tokenId, accountId: account, units, holdSeconds: hold }); reload(); }}>Reserve</button>
        {price != null && <span className="muted" style={{ alignSelf: "flex-end", fontSize: 12 }}>{kes(price)} per unit</span>}
      </div>

      <h3 style={{ marginTop: 14 }}>Open reservations</h3>
      {!open?.length && <div className="muted">None held for {tokenId || "this token"}.</div>}
      {!!open?.length && (
        <table>
          <thead><tr><th>Buyer</th><th className="num">Units</th><th className="num">Amount</th><th>Expires</th><th></th></tr></thead>
          <tbody>
            {open.map((r) => {
              const left = Math.max(0, Math.round((new Date(r.expiresAt).getTime() - now) / 1000));
              return (
                <tr key={r.reservationId}>
                  <td>{r.accountId}</td>
                  <td className="num">{num(r.units)}</td>
                  <td className="num">{kes(r.amountKes)}</td>
                  <td>{time(r.expiresAt)} <span className={`pill ${left ? "pill-info" : "pill-REVERSED"}`}>{left ? `${left}s` : "due"}</span></td>
                  <td className="row" style={{ gap: 6 }}>
                    <button onClick={async () => { await send("Purchase", "/v1/orders", { reservationId: r.reservationId, paymentMethod: "MPESA", simulateOutcome: "SUCCESS" }); reload(); }}>Purchase</button>
                    <button onClick={async () => { await send("Purchase (payment fails)", "/v1/orders", { reservationId: r.reservationId, paymentMethod: "MPESA", simulateOutcome: "FAIL" }); reload(); }}>Pay, fail</button>
                    <button className="danger" onClick={async () => { await send("Release", `/v1/reservations/${r.reservationId}`, undefined, "DELETE"); reload(); }}>Release</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}

function LastResult({ last, again }: { last: Sent | null; again: () => void }) {
  if (!last) {
    return <div className="panel muted">Submit an operation to see the gateway&apos;s reply here.</div>;
  }
  const { result } = last;
  const ok = result.status < 300;
  return (
    <div className="panel">
      <div className="spread">
        <h2>{last.op}</h2>
        <span className={`pill ${ok ? "pill-OK" : "pill-FAILED"}`}>HTTP {result.status}{result.body?.code ? ` · ${result.body.code}` : ""}</span>
      </div>
      {result.replayed && <div className="banner banner-info">Idempotent replay: the stored reply was returned and nothing moved.</div>}
      {!ok && result.body?.detail && <div className="banner banner-bad">{result.body.detail}</div>}
      {last.op.startsWith("Purchase") && ok && (
        <div className="banner banner-info">Order placed. The payment is charged after the commit; watch the tape for PAYMENT_RECEIVED and TOKEN_PURCHASED (or PAYMENT_FAILED).</div>
      )}
      <div className="muted" style={{ fontSize: 12 }}>Idempotency-Key <code>{result.key}</code></div>
      <div className="row" style={{ margin: "8px 0" }}>
        <button onClick={again}>Send again with the same key</button>
      </div>
      <pre className="json">{JSON.stringify(result.body, null, 2)}</pre>
    </div>
  );
}
