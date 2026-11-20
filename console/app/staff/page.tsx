"use client";

import { useCallback, useEffect, useState } from "react";
import { Action, get, newKey, Result, Session, time, useLive, useSession, write } from "@/lib/api";
import { Drawer, EventTimeline, Requires, ResultNote } from "@/components/ui";

type StaffUser = {
  userId: string; displayName: string; email: string | null; role: string; status: "ACTIVE" | "DISABLED";
  statusNote: string | null; createdBy: string; createdAt: string; updatedAt: string;
};
type Detail = { user: StaffUser; history: Action[]; recentActions: Action[] };

const ROLES: [string, string, string][] = [
  ["admin", "Platform admin", "Tokens and supply, businesses, staff, replay"],
  ["rules-manager", "Rules manager", "Drafts, simulates and publishes rule versions"],
  ["operations", "Operations", "Onboards customers; awards, trades, transfers"],
  ["compliance", "Compliance officer", "KYC and business verification, freezes, erasure"],
  ["auditor", "Auditor", "Read-only"],
];
const roleLabel = (r: string) => ROLES.find((x) => x[0] === r)?.[1] ?? r;

export default function StaffPage() {
  const session = useSession();
  return (
    <>
      <h1>Staff</h1>
      <p className="lede">
        The people who operate the platform. Each has one role, and the role decides what their sign-in may do. Every
        action they take is recorded in the Action Ledger under their own name. Disabling a user, or changing their
        role, takes effect on their next request.
      </p>
      <Requires anyOf={["staff:manage"]} what="Managing staff is for platform admins.">
        {session && <Users session={session} />}
      </Requires>
    </>
  );
}

function Users({ session }: { session: Session }) {
  const { data, reload } = useLive<StaffUser[]>("/v1/staff");
  const [selected, setSelected] = useState<string | null>(null);
  const users = data ?? [];
  return (
    <div className="grid-main">
      <div className="panel">
        <h2>Users</h2>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Name</th><th>Role</th><th>Status</th><th>Added</th></tr></thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.userId} className="clickable" onClick={() => setSelected(u.userId)}>
                  <td><b>{u.displayName}</b>{u.userId === session.subject && <span className="pill pill-info" style={{ marginLeft: 6 }}>you</span>}<div className="muted mono">{u.userId}</div></td>
                  <td>{roleLabel(u.role)}</td>
                  <td><span className={`pill ${u.status === "ACTIVE" ? "pill-OK" : "pill-REVERSED"}`}>{u.status.toLowerCase()}</span>
                    {u.statusNote && <div className="muted">{u.statusNote}</div>}</td>
                  <td>{time(u.createdAt)}<div className="muted">by {u.createdBy}</div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div><AddUser onDone={(id) => { reload(); setSelected(id); }} /></div>
      <Drawer open={!!selected} onClose={() => { setSelected(null); reload(); }} title="Staff user">
        {selected && <UserDrawer id={selected} session={session} onChange={reload} />}
      </Drawer>
    </div>
  );
}

function AddUser({ onDone }: { onDone: (id: string) => void }) {
  const [f, setF] = useState({ displayName: "", email: "", role: "operations", userId: "" });
  const [result, setResult] = useState<Result | null>(null);
  async function submit() {
    const body: any = { displayName: f.displayName, role: f.role };
    if (f.email) body.email = f.email;
    if (f.userId) body.userId = f.userId;
    const r = await write("/v1/staff", body, newKey("staff"));
    setResult(r);
    if (r.status === 201) {
      setF({ displayName: "", email: "", role: "operations", userId: "" });
      onDone(r.body.userId);
    }
  }
  return (
    <div className="panel">
      <h2>Add a user</h2>
      <div style={{ display: "grid", gap: 8 }}>
        <label className="field">Full name<input value={f.displayName} onChange={(e) => setF({ ...f, displayName: e.target.value })} placeholder="Jane Wambui" /></label>
        <label className="field">Work email (kept out of the ledger)<input value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} placeholder="jane@example.com" /></label>
        <label className="field">Role
          <select value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })}>
            {ROLES.map(([id, l, d]) => <option key={id} value={id}>{l}: {d}</option>)}
          </select>
        </label>
        <label className="field">User id (optional)<input value={f.userId} onChange={(e) => setF({ ...f, userId: e.target.value })} placeholder="generated from the name" /></label>
        <button className="primary" onClick={submit} disabled={!f.displayName}>Add user</button>
      </div>
      <ResultNote r={result} ok="They can now sign in from the menu at the top right." />
      <div className="muted" style={{ fontSize: 12, marginTop: 10 }}>
        In the demo, anyone can sign in as any active user. A real deployment adds passwords or single sign-on through an
        identity provider; the roles and the ledger stay the same.
      </div>
    </div>
  );
}

function UserDrawer({ id, session, onChange }: { id: string; session: Session; onChange: () => void }) {
  const [d, setD] = useState<Detail | null>(null);
  const [role, setRole] = useState("");
  const [reason, setReason] = useState("");
  const [result, setResult] = useState<Result | null>(null);
  const load = useCallback(() => {
    get<Detail>(`/v1/staff/${id}`).then((x) => { setD(x); setRole(x.user.role); }).catch(() => {});
  }, [id]);
  useEffect(load, [load]);
  if (!d) return <div className="muted">Loading…</div>;
  const u = d.user;
  const self = u.userId === session.subject;
  async function act(r: Promise<Result>) {
    setResult(await r);
    load();
    onChange();
  }

  return (
    <>
      <section>
        <div className="spread"><h2 style={{ margin: 0 }}>{u.displayName}</h2>
          <span className={`pill ${u.status === "ACTIVE" ? "pill-OK" : "pill-REVERSED"}`}>{u.status.toLowerCase()}</span></div>
        <dl className="kv">
          <dt>User id</dt><dd className="mono">{u.userId}</dd>
          <dt>Role</dt><dd>{roleLabel(u.role)}</dd>
          {u.email && <><dt>Email</dt><dd>{u.email}</dd></>}
          <dt>Added</dt><dd>{time(u.createdAt)} by {u.createdBy}</dd>
          {u.statusNote && <><dt>Note</dt><dd>{u.statusNote}</dd></>}
        </dl>
      </section>

      <section>
        <h3>Access</h3>
        {self ? <div className="muted">This is you. Another admin must change your role or status.</div> : (
          <>
            <div className="row">
              <select value={role} onChange={(e) => setRole(e.target.value)}>
                {ROLES.map(([r, l]) => <option key={r} value={r}>{l}</option>)}
              </select>
              <button disabled={role === u.role} onClick={() => act(write(`/v1/staff/${id}/role`, { role }, newKey("role")))}>Change role</button>
            </div>
            <label className="field" style={{ marginTop: 10 }}>Reason (required to disable)
              <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Left the company" />
            </label>
            <div className="row" style={{ marginTop: 8 }}>
              {u.status === "ACTIVE"
                ? <button className="danger" onClick={() => act(write(`/v1/staff/${id}/status`, { status: "DISABLED", reason: reason || undefined }, newKey("disable")))}>Disable</button>
                : <button className="primary" onClick={() => act(write(`/v1/staff/${id}/status`, { status: "ACTIVE", reason: reason || undefined }, newKey("enable")))}>Re-enable</button>}
            </div>
          </>
        )}
        <ResultNote r={result} />
      </section>

      <section>
        <h3>Changes to this account</h3>
        <EventTimeline items={d.history} empty="None since the user was set up." />
      </section>
      <section>
        <h3>Recent actions by {u.displayName}</h3>
        <EventTimeline items={d.recentActions} empty="Nothing yet." />
      </section>
    </>
  );
}
