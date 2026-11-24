"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Action, call, can, get, kes, label, num, time, useLive, useSession, useStream, who } from "@/lib/api";
import { EventTape, Outcome, Stat } from "@/components/ui";

type Summary = {
  token: {
    tokenId: string; name: string; kind: "LOYALTY" | "ASSET"; assetType: string | null; backing: string | null;
    redeemValueKes: number | null; unitPriceKes: number | null; reservationMinutes: number; kycRequired: boolean;
    maxHolderBps: number | null;
  };
  capUnits: number | null;
  supply: { authorised: number; issued: number; inTreasury: number; reserved: number; redeemed: number; circulating: number; owners: number };
  value: { unitPriceKes: number | null; valuationKes: number | null; redeemValueKes: number | null; ownedValueKes: number | null; liabilityKes: number | null };
  redemption?: { kesPerUnit: number; source: string; fromRules: boolean } | null;
  activeRuleSet: string;
  issuer: { businessId: string; name: string; status: string } | null;
};

const STAGES: { name: string; types: string[] }[] = [
  { name: "Issued", types: ["TOKEN_ISSUED", "TOKEN_EARNED"] },
  { name: "Reserved", types: ["TOKEN_RESERVED"] },
  { name: "Paid", types: ["PAYMENT_RECEIVED"] },
  { name: "Owned", types: ["TOKEN_PURCHASED", "TOKEN_EARNED"] },
  { name: "Transferred", types: ["TOKEN_TRANSFERRED"] },
  { name: "Redeemed", types: ["TOKEN_REDEEMED"] },
];

export default function CommandCentre() {
  const { data: tokens, error, reload } = useLive<Summary[]>("/v1/tokens");
  const session = useSession();
  const [seeding, setSeeding] = useState(false);
  const [lit, setLit] = useState<Record<string, string>>({});
  const clear = useRef<Record<string, ReturnType<typeof setTimeout>>>({});

  useStream((a) => {
    STAGES.filter((s) => s.types.includes(a.eventType)).forEach((s) => {
      setLit((prev) => ({ ...prev, [s.name]: `${a.tokenId ?? ""} · ${who(a)}` }));
      clearTimeout(clear.current[s.name]);
      clear.current[s.name] = setTimeout(() => setLit((prev) => ({ ...prev, [s.name]: "" })), 2500);
    });
  });

  async function seed() {
    setSeeding(true);
    try {
      await call("/v1/dev/seed", { method: "POST" });
      reload();
    } finally {
      setSeeding(false);
    }
  }

  return (
    <>
      <div className="spread">
        <div>
          <h1>Token Command Centre</h1>
          <p className="lede">
            Every token on the platform. Each figure on this screen is read from the ledger&apos;s derived endpoints:
            the sum of recorded movements. Nothing is stored as a balance and nothing is computed in this browser.
          </p>
        </div>
        <div className="row">
          {session && !session.accountId && (
            <button onClick={seed} disabled={seeding}>{seeding ? "Loading…" : "Load example data"}</button>
          )}
          {(can(session, "token:award") || can(session, "token:trade")) && (
            <Link href="/flows"><button className="primary">Run the two flows →</button></Link>
          )}
        </div>
      </div>

      <div className="lifecycle" aria-label="Token lifecycle, lit as events commit">
        {STAGES.map((s, i) => (
          <span key={s.name} className="row" style={{ gap: 6 }}>
            <span className={`stage ${lit[s.name] ? "lit" : ""}`}>
              {s.name}
              {lit[s.name] && <small>{lit[s.name]}</small>}
            </span>
            {i < STAGES.length - 1 && <span className="arrow">→</span>}
          </span>
        ))}
      </div>

      {error && <div className="banner banner-bad">Cannot reach the ZenToken API: {error}</div>}

      <div className={can(session, "ledger:read") ? "grid-main" : ""}>
        <div className="cards">
          {tokens?.length === 0 && <div className="panel muted">No tokens yet. Load the example data.</div>}
          {tokens?.map((t) => <TokenCard key={t.token.tokenId} t={t} />)}
        </div>
        {can(session, "ledger:read") && <EventTape limit={40} />}
      </div>
    </>
  );
}

function TokenCard({ t }: { t: Summary }) {
  const session = useSession();
  const { token, supply, value } = t;
  const [recent, setRecent] = useState<Action[]>([]);
  const load = () =>
    get<{ recent: Action[] }>(`/v1/tokens/${token.tokenId}`).then((d) => setRecent(d.recent.slice(0, 4))).catch(() => {});
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [supply.issued, supply.inTreasury, supply.reserved, supply.redeemed, supply.circulating]);
  const asset = token.kind === "ASSET";
  return (
    <article className="panel token-card">
      <div className="spread">
        <div>
          <h2>{token.name}</h2>
          <div className="kind">
            <code>{token.tokenId}</code> · {asset ? `Real-world asset · ${token.assetType}` : "Loyalty"}
          </div>
          <div className="kind">
            Issued by {t.issuer ? (session?.accountId ? <span>{t.issuer.name}</span> : <Link href="/businesses">{t.issuer.name}</Link>) : <span className="muted">no business recorded</span>}
            {t.issuer?.status === "SUSPENDED" && <span className="pill pill-REVERSED" style={{ marginLeft: 6 }}>issuer suspended</span>}
          </div>
        </div>
        <span className={`pill ${asset ? "pill-info" : "pill-OK"}`}>{asset ? token.assetType : "Loyalty"}</span>
      </div>
      {asset ? (
        <p className="backing">Backing: {token.backing}</p>
      ) : (
        <p className="backing">Earning: rule set <code>{t.activeRuleSet}</code> · Redemption: {kes(value.redeemValueKes)} per point
          {t.redemption && <span className="muted"> ({t.redemption.fromRules ? <>set by <code>{t.redemption.source}</code></> : "token terms"})</span>}</p>
      )}
      <div className="stats">
        <Stat label="Total supply" value={num(supply.authorised)} hint="Authorised supply (ceiling enforced by the database)" />
        <Stat label="Issued" value={num(supply.issued)} />
        <Stat label="Available" value={num(supply.inTreasury)} hint="Issued units still in the treasury" />
        <Stat label="Reserved" value={num(supply.reserved)} hint="Held for open reservations" />
        <Stat label="Redeemed" value={num(supply.redeemed)} />
        <Stat label="Active owners" value={num(supply.owners)} />
        {asset ? (
          <>
            <Stat label="Unit price" value={kes(value.unitPriceKes)} />
            <Stat label="Asset valuation" value={kes(value.valuationKes)} />
            <Stat label="Investor-held value" value={kes(value.ownedValueKes)} />
          </>
        ) : (
          <>
            <Stat label="In customer hands" value={num(supply.circulating)} />
            <Stat label="Redeem value" value={kes(value.redeemValueKes)} />
            <Stat label="Liability" value={kes(value.liabilityKes)} />
          </>
        )}
      </div>
      {asset && (
        <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>
          Ownership cap {num(t.capUnits)} units ({(token.maxHolderBps ?? 0) / 100}%) · {token.reservationMinutes}-minute holds · KYC {token.kycRequired ? "required" : "not required"}
        </div>
      )}
      <h3>{session?.accountId ? "Your recent transactions" : "Recent transactions"}</h3>
      <ul className="mini-list">
        {recent.length === 0 && <li className="muted">{session?.accountId ? "None involving your account" : "None yet"}</li>}
        {recent.map((a) => (
          <li key={a.id}>
            <span>
              <Outcome outcome={a.outcome} code={a.outcome === "OK" ? label(a.eventType) : a.payload.code ?? label(a.eventType)} /> {who(a)}
            </span>
            <span className="muted">{a.payload.units ? num(Number(a.payload.units)) : ""} · {time(a.occurredAt)}</span>
          </li>
        ))}
      </ul>
    </article>
  );
}
