"use client";

import { useEffect, useMemo, useState } from "react";
import { call, kes, newKey, num, time, useLive, write } from "@/lib/api";
import { Requires } from "@/components/ui";

// --- Types ---------------------------------------------------------------------------------------

type Version = { version: string; effectiveFrom: string; publishedBy: string; publishedAt: string; publishedSeq: number; status: string; document: any };
type RuleSetView = { ruleSetId: string; activeVersion: string | null; versions: Version[] };
type Catalogue = { ruleSets: RuleSetView[]; policies: any[]; overlapResolution: string };

type Cond = { mode: "simple"; minKes: number } | { mode: "custom"; expr: string };
type Award = { mode: "fixed"; units: number } | { mode: "per"; per: number; units: number } | { mode: "custom"; expr: string };
type RuleForm = { id: string; name: string; priority: number; stopOnMatch: boolean; createdBy?: string; tokenId: string; cond: Cond; award: Award };
type Draft = { description: string; effective: "now" | "at"; at: string; rules: RuleForm[]; rate: string };

// --- Converting between the rule document and the form -------------------------------------------

const SIMPLE = /^event\.type == 'TXN_SETTLED'(?: && event\.amountKes >= (\d+))?$/;
const PER = /^event\.amountKes \/ (\d+)(?: \* (\d+))?$/;

function toForm(r: any): RuleForm {
  const m = typeof r.when === "string" ? r.when.trim().match(SIMPLE) : null;
  const u = r.then?.award?.units;
  const p = typeof u === "string" ? u.trim().match(PER) : null;
  return {
    id: r.id, name: r.name ?? r.id, priority: r.priority ?? 0, stopOnMatch: !!r.stopOnMatch, createdBy: r.createdBy,
    tokenId: r.then?.award?.tokenId ?? "LOYALTY_MPR",
    cond: m ? { mode: "simple", minKes: Number(m[1] ?? 0) } : { mode: "custom", expr: r.when ?? "" },
    award: typeof u === "number" ? { mode: "fixed", units: u } : p ? { mode: "per", per: Number(p[1]), units: Number(p[2] ?? 1) } : { mode: "custom", expr: String(u ?? "") },
  };
}

function toRule(f: RuleForm) {
  const when = f.cond.mode === "simple"
    ? f.cond.minKes > 0 ? `event.type == 'TXN_SETTLED' && event.amountKes >= ${f.cond.minKes}` : "event.type == 'TXN_SETTLED'"
    : f.cond.expr;
  const units = f.award.mode === "fixed" ? f.award.units
    : f.award.mode === "per" ? `event.amountKes / ${f.award.per}${f.award.units !== 1 ? ` * ${f.award.units}` : ""}`
    : f.award.expr;
  const rule: any = { id: f.id, name: f.name, when, then: { award: { tokenId: f.tokenId, units } } };
  if (f.priority) rule.priority = f.priority;
  if (f.stopOnMatch) rule.stopOnMatch = true;
  rule.createdBy = f.createdBy ?? "console";
  return rule;
}

/** The rule in plain words. */
function sentence(r: any) {
  const f = toForm(r);
  const when = f.cond.mode === "simple"
    ? f.cond.minKes > 0 ? `a settled transaction is at least KES ${num(f.cond.minKes)}` : "any settled transaction"
    : <code>{f.cond.expr}</code>;
  const award = f.award.mode === "fixed" ? `${num(f.award.units)} ${f.tokenId}`
    : f.award.mode === "per" ? `${num(f.award.units)} ${f.tokenId} for every KES ${num(f.award.per)} spent`
    : <><code>{f.award.expr}</code> {f.tokenId}</>;
  return <>If {when}, award {award}{f.stopOnMatch ? <b>, then stop checking</b> : ", and keep checking"}.</>;
}

function ordered(rules: any[]) {
  return rules.map((r, i) => ({ r, i })).sort((a, b) => (b.r.priority ?? 0) - (a.r.priority ?? 0) || a.i - b.i).map((x) => x.r);
}

function nextVersion(versions: Version[]) {
  const n = Math.max(0, ...versions.map((v) => Number(v.version.replace(/\D+/g, "")) || 0));
  return `v${n + 1}`;
}

function slug(s: string) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 40) || "rule";
}

const STATUS: Record<string, { label: string; cls: string; help: string }> = {
  IN_FORCE: { label: "In force", cls: "pill-OK", help: "This version decides awards right now." },
  SCHEDULED: { label: "Scheduled", cls: "pill-info", help: "Published; takes over at its effective date." },
  SUPERSEDED: { label: "Superseded", cls: "pill-DECLINED", help: "Replaced by a later version. Kept forever: past awards still cite it." },
};

// --- Page ----------------------------------------------------------------------------------------

export default function RulesPage() {
  return (
    <Requires anyOf={["rules:read"]} what="The Rule Studio is for rules managers, operations and auditors.">
      <RuleStudio />
    </Requires>
  );
}

function RuleStudio() {
  const { data, reload } = useLive<Catalogue>("/v1/rules", false);
  const ruleSet = data?.ruleSets[0];
  const inForce = ruleSet?.versions.find((v) => v.status === "IN_FORCE");
  const [viewing, setViewing] = useState<string | null>(null);
  const shown = ruleSet?.versions.find((v) => v.version === viewing) ?? inForce ?? ruleSet?.versions[0];

  const [draft, setDraft] = useState<Draft | null>(null);
  const [jsonMode, setJsonMode] = useState(false);
  const [jsonText, setJsonText] = useState("");
  const [published, setPublished] = useState<any>(null);
  const [problem, setProblem] = useState<{ detail: string; errors?: string[] } | null>(null);
  const [sim, setSim] = useState<any>(null);
  const [busy, setBusy] = useState("");

  const draftVersion = ruleSet ? nextVersion(ruleSet.versions) : "v1";
  // The token these rules award, for its own redemption terms (used when a version sets no rate).
  const tokenId = inForce?.document.rules?.[0]?.then?.award?.tokenId ?? "LOYALTY_MPR";
  const { data: token } = useLive<any>(`/v1/tokens/${tokenId}`, false);
  const termsRate: number | null = token?.token?.redeemValueKes ?? null;

  function startFrom(v: Version, change?: (d: Draft) => void) {
    const d: Draft = { description: v.document.description ?? "", effective: "now", at: "", rules: v.document.rules.map(toForm),
      rate: v.document.redemption?.kesPerUnit != null ? String(v.document.redemption.kesPerUnit) : "" };
    change?.(d);
    setDraft(d);
    setJsonMode(false);
    setSim(null);
    setProblem(null);
  }

  useEffect(() => {
    if (!draft && inForce) startFrom(inForce);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inForce?.version]);

  /** The document the draft stands for. "Immediately" means effective as of the moment it is sent. */
  function document(): any {
    if (jsonMode) {
      const d = JSON.parse(jsonText);
      return { ...d, ruleSetId: ruleSet?.ruleSetId ?? d.ruleSetId, version: draftVersion };
    }
    if (!draft) return null;
    const effectiveFrom = draft.effective === "at" && draft.at
      ? new Date(draft.at).toISOString().replace(/\.\d+Z$/, "Z")
      : new Date(Date.now() - 2000).toISOString().replace(/\.\d+Z$/, "Z");
    const doc: any = { ruleSetId: ruleSet?.ruleSetId ?? "loyalty.default", version: draftVersion, effectiveFrom, description: draft.description, rules: draft.rules.map(toRule) };
    if (draft.rate.trim()) doc.redemption = { kesPerUnit: Number(draft.rate) };
    return doc;
  }

  function safeDocument() {
    try {
      return { doc: document(), error: null as string | null };
    } catch (e: any) {
      return { doc: null, error: e.message as string };
    }
  }

  function toggleJson() {
    if (!jsonMode) {
      setJsonText(JSON.stringify(document(), null, 2));
      setJsonMode(true);
    } else {
      try {
        const d = JSON.parse(jsonText);
        setDraft({ description: d.description ?? "", effective: "at", at: d.effectiveFrom ? d.effectiveFrom.slice(0, 16) : "", rules: (d.rules ?? []).map(toForm),
          rate: d.redemption?.kesPerUnit != null ? String(d.redemption.kesPerUnit) : "" });
        setJsonMode(false);
      } catch (e: any) {
        setProblem({ detail: `The JSON does not parse: ${e.message}` });
      }
    }
  }

  async function simulate() {
    const { doc, error } = safeDocument();
    if (error) return setProblem({ detail: error });
    setBusy("sim");
    setProblem(null);
    const r = await call("/v1/rules/simulate", { method: "POST", body: { document: doc } });
    setBusy("");
    if (r.status !== 200) return setProblem(r.body);
    setSim(r.body);
  }

  async function publish() {
    const { doc, error } = safeDocument();
    if (error) return setProblem({ detail: error });
    setBusy("pub");
    setProblem(null);
    const r = await write("/v1/rules/versions", { document: doc }, newKey("publish"));
    setBusy("");
    if (r.status !== 201) return setProblem(r.body);
    setPublished(r.body);
    setDraft(null);   // the next draft starts from the newly published version
    setSim(null);
    setViewing(null);
    reload();
  }

  const update = (fn: (d: Draft) => void) => setDraft((d) => {
    if (!d) return d;
    const copy = structuredClone(d);
    fn(copy);
    return copy;
  });

  return (
    <>
      <h1>Workflow Rule Studio</h1>
      <p className="lede">
        Loyalty rules decide how many points a customer earns for a transaction. They are data, not code: you change
        them by publishing a new <b>version</b>. Published versions are never edited, and every award records the
        version that decided it, so past awards can always be explained.
      </p>

      <div className="lifecycle">
        {["1 · Read the rules in force", "2 · Draft a new version", "3 · Test it: one transaction, or all past ones", "4 · Publish"].map((s, i, a) => (
          <span key={s} className="row" style={{ gap: 6 }}>
            <span className="stage">{s}</span>
            {i < a.length - 1 && <span className="arrow">→</span>}
          </span>
        ))}
      </div>

      {!ruleSet && <div className="banner banner-info">No rule sets yet. Load the example data from the Command Centre.</div>}

      {published && (
        <div className="banner banner-ok">
          Published <b>{published.ruleSetId} {published.version}</b>. It was recorded as ledger event #{published.seq}{" "}
          and is {published.inForceNow ? "in force now: new awards use it" : `scheduled from ${time(published.effectiveFrom)}`}.
          Your next draft is <b>{draftVersion}</b>.
        </div>
      )}

      {ruleSet && shown && (
        <div className="grid-2">
          {/* ---------------- Left: what is in force, and history ---------------- */}
          <div>
            <div className="panel">
              <div className="spread">
                <h2>{shown.version === inForce?.version ? "Rules in force now" : `Viewing ${shown.version}`}</h2>
                <span className={`pill ${STATUS[shown.status]?.cls}`}>{ruleSet.ruleSetId} {shown.version} · {STATUS[shown.status]?.label}</span>
              </div>
              {shown.document.description && <p className="muted" style={{ marginTop: 0 }}>{shown.document.description}</p>}
              <ol style={{ paddingLeft: 20, margin: "8px 0" }}>
                {ordered(shown.document.rules).map((r: any) => (
                  <li key={r.id} style={{ marginBottom: 6 }}>
                    <b>{r.name ?? r.id}</b> <span className="muted">(priority {r.priority ?? 0})</span>
                    <div>{sentence(r)}</div>
                  </li>
                ))}
              </ol>
              <div className="redeem-rate">
                <b>Redemption rate:</b>{" "}
                {shown.document.redemption?.kesPerUnit != null
                  ? <>1 point = <b>{kes(shown.document.redemption.kesPerUnit)}</b>, set by this version</>
                  : <>not set in this version, so the token&apos;s own terms apply{termsRate != null ? <>: 1 point = <b>{kes(termsRate)}</b></> : null}</>}
              </div>
              <div className="banner banner-info" style={{ fontSize: 12 }}>
                <b>How overlapping rules combine:</b> rules are checked in the order above (highest priority first). Every
                rule that matches adds its award; a matching rule marked &quot;then stop checking&quot; ends the check.
              </div>
              <div className="muted" style={{ fontSize: 12 }}>
                Effective {time(shown.effectiveFrom)} · published by {shown.publishedBy} as ledger event #{shown.publishedSeq}
              </div>
              {shown.version !== inForce?.version && (
                <div className="row" style={{ marginTop: 8 }}>
                  <button onClick={() => setViewing(null)}>Back to the version in force</button>
                  <button onClick={() => startFrom(shown)}>Start a draft from {shown.version}</button>
                </div>
              )}
            </div>

            <div className="panel">
              <h2>Version history</h2>
              <table>
                <thead><tr><th>Version</th><th>Status</th><th>Effective from</th><th>Published by</th><th className="num">Rules</th><th></th></tr></thead>
                <tbody>
                  {ruleSet.versions.map((v) => (
                    <tr key={v.version} className="clickable" onClick={() => setViewing(v.version)}>
                      <td><b>{v.version}</b></td>
                      <td><span className={`pill ${STATUS[v.status]?.cls}`} title={STATUS[v.status]?.help}>{STATUS[v.status]?.label}</span></td>
                      <td>{time(v.effectiveFrom)}</td>
                      <td>{v.publishedBy}</td>
                      <td className="num">{v.document.rules.length}</td>
                      <td className="muted">{v.version === shown.version ? "viewing" : "view"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
                In force = deciding awards now · Scheduled = takes over at its date · Superseded = replaced, kept for the record.
              </div>
            </div>

            <details className="panel">
              <summary><b>Other rules, enforced outside the rule engine</b> <span className="muted">(caps, supply, KYC, timers)</span></summary>
              <div className="table-wrap" style={{ marginTop: 10 }}>
                <table>
                  <thead><tr><th>Token</th><th>Policy</th><th>Value</th><th>Enforced at</th><th>How</th></tr></thead>
                  <tbody>
                    {data?.policies.map((p, i) => (
                      <tr key={i}><td className="mono">{p.tokenId}</td><td>{p.name}</td><td>{p.value}</td>
                        <td><span className={`pill ${p.enforcedAt === "Database" ? "pill-OK" : "pill-info"}`}>{p.enforcedAt}</span></td>
                        <td className="muted">{p.mechanism}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </details>
          </div>

          {/* ---------------- Right: the draft ---------------- */}
          <div>
            <div className="panel">
              <div className="spread">
                <h2>Draft {draftVersion}</h2>
                <span className="pill pill-info">not published</span>
              </div>
              <div className="row" style={{ marginBottom: 10 }}>
                <span className="muted" style={{ fontSize: 12 }}>Start from:</span>
                {inForce && <button onClick={() => startFrom(inForce)}>Rules in force ({inForce.version})</button>}
                {inForce && <button onClick={() => startFrom(inForce, (d) => {
                  d.description = "Earn 2 points for every KES 1,000 spent";
                  d.rules = [{ id: "double-rate", name: "2 per KES 1,000", priority: 0, stopOnMatch: true, tokenId: d.rules[0]?.tokenId ?? "LOYALTY_MPR",
                    cond: { mode: "simple", minKes: 1000 }, award: { mode: "per", per: 1000, units: 2 } }];
                })}>Example: 2 per KES 1,000</button>}
                {inForce && <button onClick={() => startFrom(inForce, (d) => {
                  d.description = "Overlap: the high tier no longer stops, so a KES 60,000 spend matches both tiers";
                  d.rules.forEach((r) => { if (r.priority > 0) r.stopOnMatch = false; });
                })}>Example: overlapping tiers</button>}
                {inForce && <button onClick={() => startFrom(inForce, (d) => {
                  d.description = "Points are worth more: redeem at KES 12 a point";
                  d.rate = "12";
                })}>Example: redeem at KES 12</button>}
              </div>

              {draft && !jsonMode && (
                <>
                  <label className="field">What does this version change?
                    <input value={draft.description} onChange={(e) => update((d) => { d.description = e.target.value; })} placeholder="e.g. Double points for big spenders" />
                  </label>
                  <div className="row" style={{ margin: "10px 0" }}>
                    <span className="muted" style={{ fontSize: 12 }}>Takes effect:</span>
                    <label className="row" style={{ gap: 4 }}><input type="radio" checked={draft.effective === "now"} onChange={() => update((d) => { d.effective = "now"; })} /> immediately when published</label>
                    <label className="row" style={{ gap: 4 }}><input type="radio" checked={draft.effective === "at"} onChange={() => update((d) => { d.effective = "at"; })} /> from</label>
                    <input type="datetime-local" value={draft.at} disabled={draft.effective !== "at"} onChange={(e) => update((d) => { d.at = e.target.value; })} />
                  </div>

                  <label className="field" style={{ marginBottom: 10 }}>Redemption rate (KES per point)
                    <input type="number" min={1} step={1} value={draft.rate} onChange={(e) => update((d) => { d.rate = e.target.value; })}
                      placeholder={termsRate != null ? `blank = the token's terms (KES ${termsRate})` : "blank = the token's terms"} style={{ maxWidth: 260 }} />
                  </label>

                  {draft.rules.map((r, i) => (
                    <RuleEditor key={i} rule={r} index={i}
                      onChange={(fn) => update((d) => fn(d.rules[i]))}
                      onRemove={() => update((d) => { d.rules.splice(i, 1); })} />
                  ))}
                  <button onClick={() => update((d) => {
                    const n = d.rules.length + 1;
                    d.rules.push({ id: `rule-${n}`, name: `Rule ${n}`, priority: 0, stopOnMatch: false, tokenId: d.rules[0]?.tokenId ?? "LOYALTY_MPR",
                      cond: { mode: "simple", minKes: 1000 }, award: { mode: "fixed", units: 1 } });
                  })}>+ Add a rule</button>
                </>
              )}

              {jsonMode && (
                <>
                  <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>The rule-set document as the API receives it. The version is always set to {draftVersion}.</div>
                  <textarea rows={22} value={jsonText} onChange={(e) => setJsonText(e.target.value)} spellCheck={false} />
                </>
              )}
              <div style={{ marginTop: 8 }}>
                <button className="ghost" onClick={toggleJson}>{jsonMode ? "← Back to the form" : "Edit as JSON (advanced)"}</button>
              </div>
            </div>

            {draft || jsonMode ? <TryIt document={safeDocument} /> : null}

            <div className="panel">
              <h2>Test on past transactions, then publish</h2>
              <p className="muted" style={{ marginTop: 0, fontSize: 12 }}>
                Simulation re-runs your draft over every transaction already recorded and compares with what was actually
                awarded. It writes nothing. Publishing records a <code>RULE_VERSION_PUBLISHED</code> event; from its effective
                time, new awards use {draftVersion}.
              </p>
              <div className="row">
                <button disabled={!!busy} onClick={simulate}>{busy === "sim" ? "Simulating…" : "Simulate on past transactions"}</button>
                <button className="primary" disabled={!!busy} onClick={publish}>{busy === "pub" ? "Publishing…" : `Publish ${draftVersion}`}</button>
              </div>
              {problem && (
                <div className="banner banner-bad">
                  <b>Not published.</b> {problem.detail}
                  {problem.errors && <ul style={{ margin: "6px 0 0" }}>{problem.errors.map((e) => <li key={e}>{e}</li>)}</ul>}
                </div>
              )}
            </div>

            {sim && <Simulation sim={sim} current={inForce?.version} />}
          </div>
        </div>
      )}
    </>
  );
}

// --- One rule in the form -------------------------------------------------------------------------

function RuleEditor({ rule: r, index, onChange, onRemove }: { rule: RuleForm; index: number; onChange: (fn: (r: RuleForm) => void) => void; onRemove: () => void }) {
  return (
    <div className="panel" style={{ background: "var(--panel-2)", margin: "0 0 10px", boxShadow: "none" }}>
      <div className="spread">
        <label className="field" style={{ flex: 1 }}>Rule {index + 1} name
          <input value={r.name} onChange={(e) => onChange((x) => { x.name = e.target.value; x.id = slug(e.target.value); })} />
        </label>
        <button className="ghost" onClick={onRemove} title="Remove this rule" style={{ alignSelf: "flex-end" }}>Remove</button>
      </div>

      <div className="row" style={{ marginTop: 8 }}>
        <span style={{ fontWeight: 600 }}>When</span>
        {r.cond.mode === "simple" ? (
          <>
            <span>a settled transaction is at least KES</span>
            <input type="number" min={0} value={r.cond.minKes} style={{ width: 110 }}
              onChange={(e) => onChange((x) => { x.cond = { mode: "simple", minKes: Number(e.target.value) }; })} />
          </>
        ) : (
          <input className="mono" style={{ flex: 1 }} value={r.cond.expr} onChange={(e) => onChange((x) => { x.cond = { mode: "custom", expr: e.target.value }; })} />
        )}
        <button className="ghost" style={{ fontSize: 12 }} onClick={() => onChange((x) => {
          x.cond = x.cond.mode === "simple"
            ? { mode: "custom", expr: x.cond.minKes > 0 ? `event.type == 'TXN_SETTLED' && event.amountKes >= ${x.cond.minKes}` : "event.type == 'TXN_SETTLED'" }
            : { mode: "simple", minKes: 1000 };
        })}>{r.cond.mode === "simple" ? "custom condition" : "simple condition"}</button>
      </div>

      <div className="row" style={{ marginTop: 8 }}>
        <span style={{ fontWeight: 600 }}>Award</span>
        <select value={r.award.mode} onChange={(e) => onChange((x) => {
          const m = e.target.value;
          x.award = m === "fixed" ? { mode: "fixed", units: 1 } : m === "per" ? { mode: "per", per: 1000, units: 1 } : { mode: "custom", expr: "event.amountKes / 1000" };
        })}>
          <option value="fixed">a fixed number of points</option>
          <option value="per">points per KES spent</option>
          <option value="custom">a custom formula</option>
        </select>
        {r.award.mode === "fixed" && (
          <><input type="number" min={0} value={r.award.units} style={{ width: 90 }} onChange={(e) => onChange((x) => { x.award = { mode: "fixed", units: Number(e.target.value) }; })} /><span>{r.tokenId}</span></>
        )}
        {r.award.mode === "per" && (
          <>
            <input type="number" min={1} value={r.award.units} style={{ width: 70 }} onChange={(e) => onChange((x) => { if (x.award.mode === "per") x.award.units = Number(e.target.value); })} />
            <span>{r.tokenId} for every KES</span>
            <input type="number" min={1} value={r.award.per} style={{ width: 90 }} onChange={(e) => onChange((x) => { if (x.award.mode === "per") x.award.per = Number(e.target.value); })} />
          </>
        )}
        {r.award.mode === "custom" && (
          <input className="mono" style={{ flex: 1 }} value={r.award.expr} onChange={(e) => onChange((x) => { x.award = { mode: "custom", expr: e.target.value }; })} />
        )}
      </div>

      <div className="row" style={{ marginTop: 8 }}>
        <label className="field">Priority (higher is checked first)
          <input type="number" value={r.priority} style={{ width: 90 }} onChange={(e) => onChange((x) => { x.priority = Number(e.target.value); })} />
        </label>
        <label className="row" style={{ gap: 6, alignSelf: "flex-end" }}>
          <input type="checkbox" checked={r.stopOnMatch} onChange={(e) => onChange((x) => { x.stopOnMatch = e.target.checked; })} />
          Stop checking other rules when this one matches
        </label>
      </div>
    </div>
  );
}

// --- Try one transaction against the rules in force and the draft --------------------------------

function TryIt({ document }: { document: () => { doc: any; error: string | null } }) {
  const [amount, setAmount] = useState(60000);
  const [current, setCurrent] = useState<any>(null);
  const [proposed, setProposed] = useState<any>(null);

  async function run(kes = amount) {
    setAmount(kes);
    const transaction = { type: "TXN_SETTLED", amountKes: kes };
    const { doc, error } = document();
    const [a, b] = await Promise.all([
      call("/v1/rules/evaluate", { method: "POST", body: { transaction } }),
      error ? Promise.resolve({ status: 400, body: { detail: error } }) : call("/v1/rules/evaluate", { method: "POST", body: { document: doc, transaction } }),
    ]);
    setCurrent(a.body);
    setProposed(b.status === 200 ? b.body : { error: b.body?.detail, errors: b.body?.errors });
  }

  return (
    <div className="panel">
      <h2>Try a transaction</h2>
      <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>See what the rules in force and your draft would award. Nothing is recorded.</div>
      <div className="row">
        <label className="field">Settled spend (KES)<input type="number" min={0} value={amount} onChange={(e) => setAmount(Number(e.target.value))} style={{ width: 130 }} /></label>
        <button className="primary" style={{ alignSelf: "flex-end" }} onClick={() => run()}>Check</button>
        {[800, 1200, 60000].map((v) => <button key={v} className="ghost" style={{ alignSelf: "flex-end" }} onClick={() => run(v)}>KES {num(v)}</button>)}
      </div>
      {current && (
        <div className="grid-2" style={{ gap: 10, marginTop: 10 }}>
          <Verdict title={`In force (${current.ruleSet ?? "—"})`} result={current} />
          <Verdict title="Your draft" result={proposed} />
        </div>
      )}
    </div>
  );
}

function Verdict({ title, result }: { title: string; result: any }) {
  if (!result) return null;
  if (result.error) {
    return (
      <div className="panel" style={{ margin: 0 }}>
        <h3>{title}</h3>
        <div className="banner banner-bad">{result.error}{result.errors && <ul>{result.errors.map((e: string) => <li key={e}>{e}</li>)}</ul>}</div>
      </div>
    );
  }
  const total = Object.values(result.awarded ?? {}).reduce((a: number, b: any) => a + Number(b), 0);
  return (
    <div className="panel" style={{ margin: 0 }}>
      <h3>{title}</h3>
      <div className="big">{num(total as number)} <span className="muted" style={{ fontSize: 13 }}>points</span></div>
      <ul className="mini-list" style={{ marginTop: 6 }}>
        {result.trace.map((s: any) => (
          <li key={s.ruleId}>
            <span>{s.ruleId}</span>
            <span className={s.matched ? "delta-up" : "muted"}>{s.matched ? `matched +${num(s.units)}${s.stopped ? ", stopped" : ""}` : "no match"}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// --- Simulation over history -------------------------------------------------------------------

function Simulation({ sim, current }: { sim: any; current?: string }) {
  const delta = (n: number) => <span className={n > 0 ? "delta-up" : n < 0 ? "delta-down" : "muted"}>{n > 0 ? "+" : ""}{num(n)}</span>;
  return (
    <div className="panel">
      <h2>Simulation: what was awarded vs your draft</h2>
      <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
        {num(sim.eventsEvaluated)} past transactions re-checked. Nothing was written. &quot;Awarded&quot; is what each
        transaction actually earned, under the version in force at the time (now {current}).
      </div>
      <div className="grid-2" style={{ gap: 10 }}>
        <div className="panel" style={{ margin: 0 }}><h3>Awarded</h3><div className="big">{num(sim.actualUnits)}</div><div className="muted">points</div></div>
        <div className="panel" style={{ margin: 0 }}><h3>Under your draft</h3><div className="big">{num(sim.simulatedUnits)}</div><div className="muted">{delta(sim.deltaUnits)} points · {num(sim.eventsChanged)} transactions change</div></div>
      </div>
      <h3 style={{ marginTop: 14 }}>By customer</h3>
      <table>
        <thead><tr><th>Customer</th><th className="num">Awarded</th><th className="num">Under draft</th><th className="num">Change</th></tr></thead>
        <tbody>
          {sim.byAccount.map((a: any) => (
            <tr key={a.accountId}><td>{a.accountId}</td><td className="num">{num(a.actual)}</td><td className="num">{num(a.simulated)}</td><td className="num">{delta(a.delta)}</td></tr>
          ))}
        </tbody>
      </table>
      <details style={{ marginTop: 12 }}>
        <summary>Transaction by transaction</summary>
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table>
            <thead><tr><th>#</th><th>Customer</th><th className="num">Spend</th><th>Decided by</th><th className="num">Awarded</th><th className="num">Draft</th></tr></thead>
            <tbody>
              {sim.rows.map((r: any) => (
                <tr key={r.seq}>
                  <td className="mono">{r.seq}</td><td>{r.accountId}</td><td className="num">KES {num(r.amountKes)}</td>
                  <td className="mono">{r.recordedVersion} · {r.actualRules || "no match"}</td>
                  <td className="num">{num(r.actualUnits)}</td>
                  <td className="num">{r.simulatedUnits === r.actualUnits ? num(r.simulatedUnits) : <b>{num(r.simulatedUnits)}</b>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}
