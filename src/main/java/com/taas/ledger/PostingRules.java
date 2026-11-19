package com.taas.ledger;

import java.util.List;

/**
 * The single place that turns an event into balance changes. Live writes and replay both go through
 * here, which is why replaying the Action Ledger always reproduces the same balances.
 *
 * <p>Every rule is double-entry: one account is debited and another credited by the same amount, so
 * the legs of every event sum to zero and tokens are never created or lost outside the mint and burn
 * accounts.
 */
public final class PostingRules {

    public record Leg(String account, long amount) {
    }

    private PostingRules() {
    }

    public static List<Leg> legsFor(ActionEvent event) {
        return switch (event.type()) {
            case TOKEN_CREATED -> List.of();
            case ISSUED, EARNED -> move(Accounts.MINT, event.get("to"), event.getLong("amount"));
            case TRANSFERRED -> move(event.get("from"), event.get("to"), event.getLong("amount"));
            case RESERVED -> move(event.get("seller"), Accounts.hold(event.getUuid("reservationId")),
                    event.getLong("amount"));
            case PURCHASED -> move(Accounts.hold(event.getUuid("reservationId")), event.get("buyer"),
                    event.getLong("amount"));
            case RESERVATION_RELEASED, RESERVATION_EXPIRED -> move(Accounts.hold(event.getUuid("reservationId")),
                    event.get("seller"), event.getLong("amount"));
            case REDEEMED -> move(event.get("from"), Accounts.BURN, event.getLong("amount"));
        };
    }

    private static List<Leg> move(String from, String to, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Posting amount must be positive, was " + amount);
        }
        return List.of(new Leg(from, -amount), new Leg(to, amount));
    }
}
