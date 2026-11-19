package com.taas.ledger;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taas.common.LedgerIntegrityException;

/**
 * Applies events to the projections (Token Ledger, tokens, reservations). The live write path and the
 * replay both call {@link #apply}, so there is exactly one definition of how history becomes state.
 */
@Component
public class Projector {

    public record ReplayReport(long eventsReplayed, boolean hashChainValid, Map<String, Long> balancesBefore,
            Map<String, Long> balancesAfter, boolean balancesIdentical, boolean reservationsIdentical,
            Map<String, String> differences) {
    }

    public record VerifyReport(long events, boolean hashChainValid, Long firstBrokenSeq,
            boolean balanceTableMatchesPostings, List<Long> unbalancedEvents, boolean consistent) {
    }

    private final ActionLedger actions;
    private final TokenLedger tokenLedger;
    private final Registry registry;

    public Projector(ActionLedger actions, TokenLedger tokenLedger, Registry registry) {
        this.actions = actions;
        this.tokenLedger = tokenLedger;
        this.registry = registry;
    }

    public void apply(ActionEvent event) {
        List<PostingRules.Leg> legs = PostingRules.legsFor(event);
        long sum = legs.stream().mapToLong(PostingRules.Leg::amount).sum();
        if (sum != 0) {
            throw new IllegalStateException("Event " + event.seq() + " does not balance: " + legs);
        }
        legs.forEach(leg -> tokenLedger.post(event, leg));

        switch (event.type()) {
            case TOKEN_CREATED -> registry.insertToken(event);
            case RESERVED -> registry.insertReservation(event);
            case PURCHASED -> registry.closeReservation(event, Registry.ReservationStatus.PURCHASED);
            case RESERVATION_RELEASED -> registry.closeReservation(event, Registry.ReservationStatus.RELEASED);
            case RESERVATION_EXPIRED -> registry.closeReservation(event, Registry.ReservationStatus.EXPIRED);
            default -> {
                // ISSUED, EARNED, TRANSFERRED, REDEEMED only move balances.
            }
        }
    }

    /**
     * The replay test: throw away every projection, rebuild it from the Action Ledger alone (checking the
     * hash chain on the way), and compare with what was there before. Runs in one transaction, so a broken
     * chain rolls everything back and leaves the projections as they were.
     */
    @Transactional
    public ReplayReport replay() {
        actions.lockForWrite();
        Map<String, Long> before = tokenLedger.snapshot();
        Map<String, String> reservationsBefore = registry.reservationSnapshot();

        tokenLedger.wipe();
        registry.wipe();

        long[] count = {0};
        String[] prevHash = {ActionLedger.GENESIS_HASH};
        actions.forEachInOrder(event -> {
            if (!event.prevHash().equals(prevHash[0]) || !event.expectedHash().equals(event.hash())) {
                throw new LedgerIntegrityException("Action Ledger hash chain is broken at seq " + event.seq()
                        + "; replay aborted and projections left unchanged");
            }
            apply(event);
            prevHash[0] = event.hash();
            count[0]++;
        });

        Map<String, Long> after = tokenLedger.snapshot();
        Map<String, String> reservationsAfter = registry.reservationSnapshot();
        return new ReplayReport(count[0], true, before, after, before.equals(after),
                reservationsBefore.equals(reservationsAfter), diff(before, after));
    }

    /** Read-only integrity check: hash chain, balance table vs postings, and double-entry per event. */
    @Transactional(readOnly = true)
    public VerifyReport verify() {
        long[] count = {0};
        String[] prevHash = {ActionLedger.GENESIS_HASH};
        Long[] broken = {null};
        actions.forEachInOrder(event -> {
            count[0]++;
            if (broken[0] == null
                    && (!event.prevHash().equals(prevHash[0]) || !event.expectedHash().equals(event.hash()))) {
                broken[0] = event.seq();
            }
            prevHash[0] = event.hash();
        });
        boolean tableMatches = tokenLedger.snapshot().equals(tokenLedger.sumOfPostings());
        List<Long> unbalanced = tokenLedger.unbalancedEvents();
        return new VerifyReport(count[0], broken[0] == null, broken[0], tableMatches, unbalanced,
                broken[0] == null && tableMatches && unbalanced.isEmpty());
    }

    private static Map<String, String> diff(Map<String, Long> before, Map<String, Long> after) {
        Map<String, String> out = new TreeMap<>();
        before.forEach((key, value) -> {
            if (!value.equals(after.get(key))) {
                out.put(key, value + " -> " + after.getOrDefault(key, 0L));
            }
        });
        after.forEach((key, value) -> {
            if (!before.containsKey(key)) {
                out.put(key, "0 -> " + value);
            }
        });
        return out;
    }
}
