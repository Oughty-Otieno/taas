"use client";

import { useEffect, useState } from "react";
import { Action, can, get, label, Result, short, time, who, useSession, useStream } from "@/lib/api";

export function Outcome({ outcome, code }: { outcome: string; code?: string }) {
  const text = code ?? { OK: "Committed", DECLINED: "Declined", REVERSED: "Reversed", FAILED: "Failed" }[outcome] ?? outcome;
  return <span className={`pill pill-${outcome}`}>{text}</span>;
}

export function Stat({ label: l, value, hint }: { label: string; value: React.ReactNode; hint?: string }) {
  return (
    <div className="stat" title={hint}>
      <div className="stat-label">{l}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}

export function Check({ ok, children }: { ok: boolean | null | undefined; children: React.ReactNode }) {
  return <span className={`check ${ok ? "check-ok" : ok === false ? "check-bad" : ""}`}>{ok ? "✓" : ok === false ? "✗" : "…"} {children}</span>;
}

/**
 * The tape: committed events as they arrive. Failures, declines and reversals are coloured differently from
 * successes, so an expired reservation or a rejected transfer is visible at a glance.
 */
export function EventTape({ limit = 30, tokenId, onSelect }: { limit?: number; tokenId?: string; onSelect?: (a: Action) => void }) {
  const [items, setItems] = useState<Action[]>([]);
  const [fresh, setFresh] = useState<string | null>(null);
  useEffect(() => {
    const q = tokenId ? `&tokenId=${tokenId}` : "";
    get<{ items: Action[] }>(`/v1/ledger/actions?limit=${limit}${q}`).then((p) => setItems(p.items)).catch(() => {});
  }, [limit, tokenId]);
  const session = useSession();
  const live = useStream((a) => {
    if (tokenId && a.tokenId !== tokenId) return;
    setItems((prev) => (prev.some((p) => p.id === a.id) ? prev : [a, ...prev].slice(0, limit)));
    setFresh(a.id);
  });
  return (
    <div className="tape">
      <div className="tape-head">
        <span>Live events</span>
        <span className={`dot ${live ? "dot-on" : ""}`}>{live ? "streaming" : "connecting"}</span>
      </div>
      {session && !can(session, "ledger:read") && <div className="muted pad">This sign-in cannot read the Action Ledger.</div>}
      {items.length === 0 && can(session, "ledger:read") && <div className="muted pad">No events yet. Load the example data to begin.</div>}
      {items.map((a) => (
        <button key={a.id} className={`tape-row out-${a.outcome} ${fresh === a.id ? "fresh" : ""}`} onClick={() => onSelect?.(a)}>
          <span className="tape-seq">#{a.seq}</span>
          <span className="tape-type">{a.eventType === "REQUEST_REJECTED" ? `${(a.payload.operation ?? "").toUpperCase()} REJECTED` : label(a.eventType)}</span>
          <span className="tape-who">{who(a)}</span>
          <span className="tape-units">{a.payload.units ? `${Number(a.payload.units).toLocaleString()} ${a.tokenId ?? ""}` : a.payload.code ?? a.tokenId ?? a.subjectId}</span>
          <span className="tape-time">{time(a.occurredAt)}</span>
        </button>
      ))}
    </div>
  );
}

export function Drawer({ open, onClose, title, children }: { open: boolean; onClose: () => void; title: string; children: React.ReactNode }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [onClose]);
  if (!open) return null;
  return (
    <div className="drawer-wrap" onClick={onClose}>
      <aside className="drawer" onClick={(e) => e.stopPropagation()}>
        <header>
          <h2>{title}</h2>
          <button className="ghost" onClick={onClose} aria-label="Close">✕</button>
        </header>
        <div className="drawer-body">{children}</div>
      </aside>
    </div>
  );
}

export function Hash({ value }: { value: string }) {
  return <code className="hash" title={value}>{short(value, 12)}</code>;
}

export function Json({ value }: { value: unknown }) {
  return <pre className="json">{JSON.stringify(value, null, 2)}</pre>;
}

/** Shows the screen only if the signed-in role has one of the scopes it needs; otherwise nothing (the nav redirects). */
export function Requires({ anyOf, children }: { anyOf: string[]; what?: string; children: React.ReactNode }) {
  const session = useSession();
  if (!session) return <div className="muted pad">Signing in…</div>;
  if (anyOf.some((s) => can(session, s))) return <>{children}</>;
  return null;
}

/** The answer to a write: "Done", or the refusal's code and reason. */
export function ResultNote({ r, ok: okText }: { r: Result | null; ok?: string }) {
  if (!r) return null;
  const ok = r.status < 300;
  return (
    <div className={`banner ${ok ? "banner-ok" : "banner-bad"}`}>
      {ok ? <>Done ({r.status}). {okText}</> : <><b>{r.body?.code ?? r.body?.title ?? `HTTP ${r.status}`}</b> {r.body?.detail}</>}
    </div>
  );
}

/** Ledger events about one subject, newest first, with who did each and why. */
export function EventTimeline({ items, empty = "No events." }: { items: Action[]; empty?: string }) {
  if (!items.length) return <div className="muted">{empty}</div>;
  return (
    <ul className="timeline">
      {items.map((a) => (
        <li key={a.id} className={`t-${a.outcome}`}>
          <b>{a.eventType === "REQUEST_REJECTED" ? `${(a.payload.operation ?? "").replace(/-/g, " ")} rejected` : label(a.eventType)}</b>{" "}
          <span className="muted">#{a.seq} · {time(a.occurredAt)} · {a.actorId}</span>
          {(a.payload.reason || a.payload.code || a.tokenId || a.payload.toRole) && (
            <div className="muted">
              {a.payload.code && <code>{a.payload.code}</code>} {a.tokenId && a.eventType !== "REQUEST_REJECTED" && `${a.tokenId}. `}
              {a.payload.fromRole && `${a.payload.fromRole} → ${a.payload.toRole}. `}
              {a.payload.reason}
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
