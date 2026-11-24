"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { API, signIn, useSession, useStream } from "@/lib/api";
import { canSee, SCREENS, screenFor } from "@/lib/access";


type Persona = { id: string; label: string; description: string; scopes: string[]; boundToAccount: boolean };
type StaffUser = { userId: string; displayName: string; role: string; roleLabel: string };

export default function Nav() {
  const path = usePathname();
  const live = useStream();
  const session = useSession();
  const [open, setOpen] = useState(false);
  const [personas, setPersonas] = useState<Persona[]>([]);
  const current = personas.find((p) => p.id === session?.persona);
  const router = useRouter();
  const here = screenFor(path);
  const allowed = !session || !here || canSee(session, here);

  // A screen this sign-in may not use is not offered at all: opening its address goes to the Command Centre.
  useEffect(() => {
    if (!allowed) router.replace("/");
  }, [allowed, router]);

  useEffect(() => {
    fetch(`${API}/v1/dev/personas`).then((r) => r.json()).then(setPersonas).catch(() => {});
  }, []);

  return (
    <nav className="nav">
      <div className="brand">
        <span className="brand-mark">Z</span>
        <span>ZenToken</span>
      </div>
      <div className="nav-links">
        {SCREENS.filter((l) => canSee(session, l)).map((l) => (
          <Link key={l.href} href={l.href} className={path === l.href ? "active" : ""}>
            {l.label}
          </Link>
        ))}
      </div>
      {(!session || session.scopes.includes("ledger:read")) && (
        <span className={`dot ${live ? "dot-on" : ""}`}>{live ? "ledger live" : "connecting"}</span>
      )}
      <div style={{ position: "relative" }}>
        <button onClick={() => setOpen((o) => !o)} title={session?.scopes.join(" ")}>
          {session?.name ? `${session.name} · ` : ""}
          {current ? current.label : session?.persona ?? "Signing in…"}
          {session?.accountId ? ` · ${session.accountId}` : ""} ▾
        </button>
        {open && <SignIn personas={personas} onClose={() => setOpen(false)} />}
      </div>
    </nav>
  );
}

function SignIn({ personas, onClose }: { personas: Persona[]; onClose: () => void }) {
  const [accounts, setAccounts] = useState<{ accountId: string; displayName: string; kycStatus: string }[]>([]);
  const [account, setAccount] = useState("john");
  const [users, setUsers] = useState<StaffUser[]>([]);
  useEffect(() => {
    fetch(`${API}/v1/dev/users`).then((r) => r.json()).then(setUsers).catch(() => {});
  }, []);

  useEffect(() => {
    // Listing who can sign in is itself a staff read: borrow an auditor's token for it.
    fetch(`${API}/v1/dev/token`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ persona: "auditor" }) })
      .then((r) => r.json())
      .then((t) => fetch(`${API}/v1/accounts`, { headers: { Authorization: `Bearer ${t.access_token}` } }))
      .then((r) => r.json())
      .then((a) => setAccounts(a.filter((x: any) => !x.accountId.startsWith("bidder"))))
      .catch(() => {});
  }, []);

  return (
    <div className="signin">
      <div className="spread" style={{ marginBottom: 6 }}>
        <b>Sign in as</b>
        <button className="ghost" onClick={onClose}>✕</button>
      </div>
      <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
        Each role gets a token with only its scopes. A real deployment signs people in through an identity provider.
      </div>
      {personas.filter((p) => p.id === "demo").map((p) => (
        <button key={p.id} className="signin-row" onClick={() => signIn(p.id)}>
          <b>{p.label}</b>
          <span className="muted">{p.description}</span>
        </button>
      ))}
      <div className="muted" style={{ fontSize: 12, margin: "10px 0 4px" }}>Staff (managed on the Staff page)</div>
      {users.map((u) => {
        const p = personas.find((x) => x.id === u.role);
        return (
          <button key={u.userId} className="signin-row" onClick={() => signIn(u.role, null, u.userId)} title={p?.scopes.join(" ")}>
            <b>{u.displayName}</b>
            <span className="muted">{u.roleLabel}{p ? ` · ${p.description}` : ""}</span>
          </button>
        );
      })}
      {personas.filter((p) => p.boundToAccount).map((p) => (
        <div key={p.id} className="signin-row">
          <b>{p.label}</b>
          <span className="muted">{p.description}</span>
          <div className="row" style={{ marginTop: 6 }}>
            <select value={account} onChange={(e) => setAccount(e.target.value)}>
              {accounts.map((a) => <option key={a.accountId} value={a.accountId}>{a.displayName} ({a.accountId}, KYC {a.kycStatus.toLowerCase()})</option>)}
            </select>
            <button className="primary" onClick={() => signIn(p.id, account)}>Sign in</button>
          </div>
        </div>
      ))}
    </div>
  );
}
