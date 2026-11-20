package com.taas.ledger;

import java.util.regex.Pattern;

/**
 * Account naming. Holder accounts are caller-chosen ids; system accounts start with '@' so a caller can never
 * name one, and the database never applies the ownership cap to them.
 */
public final class Accounts {

    /** The supply boundary: issuing debits it, so its balance is minus everything ever issued. */
    public static final String MINT = "@mint";
    /** Issued units not yet sold or awarded. */
    public static final String TREASURY = "@treasury";
    /** Where redeemed units go: its balance is everything ever retired. */
    public static final String BURN = "@burn";

    private static final Pattern HOLDER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private Accounts() {
    }

    public static boolean isHolder(String account) {
        return account != null && HOLDER.matcher(account).matches();
    }

    public static boolean isSystem(String account) {
        return account != null && account.startsWith("@");
    }
}
