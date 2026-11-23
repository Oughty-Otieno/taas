import { Session } from "./api";

/**
 * The console's screens and who may see them. A screen is listed in the nav, and can be opened, only when the
 * sign-in's token carries one of its scopes; `staff` screens are also hidden from customers (account-bound
 * sign-ins). The API enforces the same scopes on every call; this only keeps the console from offering a
 * screen the sign-in cannot use.
 */
export type Screen = { href: string; label: string; anyOf?: string[]; staff?: boolean };

export const SCREENS: Screen[] = [
  { href: "/", label: "Command Centre" },
  { href: "/operations", label: "Operations", anyOf: ["token:issue", "token:award", "token:trade", "token:transfer", "token:redeem"] },
  { href: "/flows", label: "Two Flows, One Platform", anyOf: ["token:award", "token:trade"] },
  { href: "/customers", label: "Customers & KYC", anyOf: ["customer:read", "kyc:submit"] },
  { href: "/businesses", label: "Businesses", anyOf: ["token:read"], staff: true },
  { href: "/ledger", label: "Action Ledger", anyOf: ["ledger:read"] },
  { href: "/rules", label: "Rule Studio", anyOf: ["rules:read"] },
  { href: "/staff", label: "Staff", anyOf: ["staff:manage"] },
];

export function canSee(session: Session | null, screen: Screen): boolean {
  if (!session) return false;
  if (screen.staff && session.accountId) return false;
  return !screen.anyOf || screen.anyOf.some((s) => session.scopes.includes(s));
}

/** The screen a path belongs to, or undefined for paths the list does not cover. */
export function screenFor(path: string): Screen | undefined {
  return SCREENS.find((s) => s.href === path) ?? SCREENS.find((s) => s.href !== "/" && path.startsWith(s.href + "/"));
}
