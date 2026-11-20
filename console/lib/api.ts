"use client";

import { useCallback, useEffect, useRef, useState } from "react";

export const API = process.env.NEXT_PUBLIC_TAAS_API ?? "http://localhost:8087";

/** A ledger event as the API returns it. */
export type Action = {
  id: string;
  seq: number;
  eventType: string;
  outcome: "OK" | "DECLINED" | "REVERSED" | "FAILED";
  subjectId: string;
  tokenId: string | null;
  actorId: string;
  idempotencyKey: string;
  ruleVersion: string;
  payload: Record<string, string>;
  occurredAt: string;
  prevHash: string;
  hash: string;
};

export type Result<T = any> = { status: number; body: T; replayed: boolean; key?: string };

export type Session = { persona: string; accountId: string | null; subject: string; scopes: string[]; name: string | null; userId: string | null };

const SESSION_KEY = "taas.signin";
let tokenPromise: Promise<string> | null = null;
let session: Session | null = null;
const sessionListeners = new Set<(s: Session | null) => void>();

type SignIn = { persona: string; accountId?: string | null; userId?: string | null };

function stored(): SignIn {
  try {
    const v = JSON.parse(localStorage.getItem(SESSION_KEY) ?? "null");
    if (v?.persona || v?.userId) return v;
  } catch {
    // private window or blocked storage: fall back to the demo operator
  }
  return { persona: "demo" };
}

/**
 * Signs in as a role. The identity provider here is the dev token endpoint: it issues a token carrying only the
 * role's scopes and, for a customer, the one account they may act for. The whole console reloads under it.
 */
export function signIn(persona: string, accountId?: string | null, userId?: string | null) {
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify({ persona, accountId: accountId ?? null, userId: userId ?? null }));
  } catch {
    // ignore: the sign-in then lasts for this page load only
  }
  window.location.reload();
}

/**
 * The console is an OAuth client: it gets a scoped access token from the identity provider and sends it as a
 * bearer token on every call.
 */
export function accessToken(): Promise<string> {
  if (!tokenPromise) {
    const want = stored();
    tokenPromise = fetch(`${API}/v1/dev/token`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      // A named staff user signs in as themselves; otherwise as a role (the demo operator, or a customer).
      body: JSON.stringify(want.userId ? { userId: want.userId } : { persona: want.persona, accountId: want.accountId ?? undefined }),
    })
      .then(async (r) => {
        if (!r.ok) {
          // A stale sign-in (say, an account that no longer exists): fall back to the demo operator.
          if (want.persona !== "demo" || want.userId) {
            try { localStorage.removeItem(SESSION_KEY); } catch { /* ignore */ }
          }
          throw new Error(`sign-in failed (${r.status})`);
        }
        return r.json();
      })
      .then((j) => {
        session = { persona: j.persona, accountId: j.accountId ?? null, subject: j.subject, scopes: String(j.scope).split(" "),
          name: j.name ?? null, userId: j.userId ?? null };
        sessionListeners.forEach((l) => l(session));
        return j.access_token as string;
      })
      .catch((e) => {
        tokenPromise = null;
        throw e;
      });
  }
  return tokenPromise;
}

/** Who is signed in, and what their token allows. */
export function useSession() {
  const [s, setS] = useState<Session | null>(session);
  useEffect(() => {
    sessionListeners.add(setS);
    accessToken().catch(() => {});
    return () => {
      sessionListeners.delete(setS);
    };
  }, []);
  return s;
}

export function can(s: Session | null, scope: string) {
  return !!s?.scopes.includes(scope);
}

export function newKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`;
}

export async function call<T = any>(
  path: string,
  init: { method?: string; body?: unknown; key?: string } = {},
): Promise<Result<T>> {
  const token = await accessToken();
  const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  if (init.key) headers["Idempotency-Key"] = init.key;
  const res = await fetch(`${API}${path}`, {
    method: init.method ?? "GET",
    headers,
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  if (res.status === 401 && stored().userId) {
    // The user was disabled or their role changed: sign in again on the next call (a disabled user falls back
    // to the demo operator, because the sign-in itself is refused).
    tokenPromise = null;
  }
  const text = await res.text();
  let body: any = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  return { status: res.status, body, replayed: res.headers.get("Idempotent-Replay") === "true", key: init.key };
}

export async function get<T = any>(path: string): Promise<T> {
  const r = await call<T>(path);
  if (r.status >= 400) throw new Error((r.body as any)?.detail ?? `GET ${path} returned ${r.status}`);
  return r.body;
}

/** Every write carries an Idempotency-Key: pass the same key to retry, a new one for a new request. */
export function write<T = any>(path: string, body: unknown, key = newKey("console"), method = "POST") {
  return call<T>(path, { method, body, key });
}

type Listener = (a: Action) => void;
const listeners = new Set<Listener>();
const statusListeners = new Set<(s: boolean) => void>();
let source: EventSource | null = null;
let connected = false;

function setConnected(v: boolean) {
  connected = v;
  statusListeners.forEach((l) => l(v));
}

let starting: Promise<void> | null = null;

/**
 * Opens the one live stream this tab shares. Every useStream on a page calls this at mount, all before the access
 * token has arrived, so concurrent callers share one pending start: without that each opened its own EventSource,
 * and a few tabs used up the browser's six connections per host, leaving ordinary requests queued forever.
 */
function ensureStream(): Promise<void> {
  if (source || typeof window === "undefined") return Promise.resolve();
  if (!starting) {
    starting = openStream().finally(() => {
      starting = null;
    });
  }
  return starting;
}

async function openStream() {
  const token = await accessToken();
  if (source) return;
  if (!session?.scopes.includes("ledger:read")) return;   // this sign-in may not watch the ledger
  // EventSource cannot set headers; the stream accepts the token as a query parameter.
  const es = new EventSource(`${API}/v1/ledger/stream?access_token=${encodeURIComponent(token)}`);
  source = es;
  es.addEventListener("hello", () => setConnected(true));
  es.addEventListener("action", (e) => {
    const a = JSON.parse((e as MessageEvent).data) as Action;
    listeners.forEach((l) => l(a));
  });
  es.onerror = () => {
    setConnected(false);
    es.close();
    if (source === es) source = null;
    setTimeout(() => ensureStream().catch(() => {}), 2000);
  };
}

/** Committed events, live from the outbox. */
export function useStream(onAction?: Listener) {
  const [live, setLive] = useState(connected);
  const ref = useRef(onAction);
  ref.current = onAction;
  useEffect(() => {
    const l: Listener = (a) => ref.current?.(a);
    listeners.add(l);
    statusListeners.add(setLive);
    ensureStream().catch(() => setLive(false));
    return () => {
      listeners.delete(l);
      statusListeners.delete(setLive);
    };
  }, []);
  return live;
}

/** Loads a GET endpoint, and loads it again (debounced) whenever a committed event arrives. */
export function useLive<T = any>(path: string | null, refreshOnEvents = true) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const load = useCallback(() => {
    if (!path) return;
    get<T>(path)
      .then((d) => {
        setData(d);
        setError(null);
      })
      .catch((e) => setError(String(e.message ?? e)));
  }, [path]);
  useEffect(load, [load]);
  useStream(() => {
    if (!refreshOnEvents) return;
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(load, 250);
  });
  return { data, error, reload: load };
}

// --- Formatting -------------------------------------------------------------------------------------

export const num = (n: number | null | undefined) => (n == null ? "—" : n.toLocaleString("en-KE"));
export const kes = (n: number | null | undefined) => (n == null ? "—" : `KES ${n.toLocaleString("en-KE")}`);
export const short = (h: string | null | undefined, n = 10) => (h ? `${h.slice(0, n)}…` : "—");

export function time(iso: string) {
  const d = new Date(iso);
  return d.toLocaleString("en-KE", { hour12: false, day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function label(type: string) {
  return type.replace(/^TOKEN_/, "").replace(/_/g, " ");
}

/** Who an event is about, for one-line display. */
export function who(a: Action) {
  const p = a.payload;
  if (p.fromAccountId) return `${p.fromAccountId} → ${p.toAccountId}`;
  if (p.accountId) return p.accountId;
  return a.actorId;
}
