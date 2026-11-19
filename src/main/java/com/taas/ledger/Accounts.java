package com.taas.ledger;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Account naming. Holder accounts are caller-chosen ids; system accounts start with '@' so a caller
 * can never name one.
 */
public final class Accounts {

    /** Where supply comes from: issuing debits it, so its balance is minus everything ever issued. */
    public static final String MINT = "@mint";
    /** Where redeemed tokens go: its balance is everything ever retired. */
    public static final String BURN = "@burn";
    private static final String HOLD_PREFIX = "@hold:";

    private static final Pattern HOLDER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private Accounts() {
    }

    /** Escrow account holding the tokens of one reservation until it is purchased, released or expires. */
    public static String hold(UUID reservationId) {
        return HOLD_PREFIX + reservationId;
    }

    public static boolean isHolder(String account) {
        return account != null && HOLDER.matcher(account).matches();
    }
}
