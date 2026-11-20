package com.taas.ledger;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taas.common.LedgerIntegrityException;

/**
 * Applies events to the Token Ledger and the workflow projections. The live write path and the replay both
 * call {@link #apply}, so there is exactly one definition of how history becomes state.
 */
@Component
public class Projector {

    /** {@code comparedWith} says what the rebuilt state was checked against. */
    public record ReplayReport(long eventsReplayed, long movementsRebuilt, boolean hashChainValid,
            String comparedWith, Map<String, Long> holdingsBefore, Map<String, Long> holdingsAfter,
            boolean holdingsIdentical, boolean workflowIdentical, Map<String, String> differences) {
    }

    private record Snapshot(Map<String, Long> holdings, Map<String, String> workflow) {
    }

    public record VerifyReport(long events, boolean hashChainValid, Long firstBrokenSeq, String headHash,
            boolean holdingsMatchMovements, List<Long> unbalancedActions, boolean consistent) {
    }

    private final ActionLedger actions;
    private final TokenLedger tokenLedger;
    private final Registry registry;
    private final JdbcTemplate jdbc;
    /** What the projections held when they were last wiped, so the replay that follows has something to match. */
    private volatile Snapshot beforeWipe;

    public Projector(ActionLedger actions, TokenLedger tokenLedger, Registry registry, JdbcTemplate jdbc) {
        this.actions = actions;
        this.tokenLedger = tokenLedger;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /** Projects one event. Returns how many movements it wrote. */
    public int apply(ActionEvent event) {
        if (event.type() == EventType.TOKEN_DEFINED) {
            registry.insertToken(event);   // before any movement, so holdings can take the token's limits
        }
        List<MovementRules.Leg> legs = MovementRules.legsFor(event);
        long sum = legs.stream().mapToLong(MovementRules.Leg::units).sum();
        if (sum != 0) {
            throw new IllegalStateException("Event " + event.seq() + " does not balance: " + legs);
        }
        legs.forEach(leg -> tokenLedger.insert(event, leg));

        switch (event.type()) {
            case TOKEN_RESERVED -> registry.insertReservation(event);
            case TOKEN_RESERVATION_RELEASED -> registry.closeReservation(event, Registry.ReservationStatus.RELEASED);
            case TOKEN_RESERVATION_EXPIRED -> {
                registry.closeReservation(event, Registry.ReservationStatus.EXPIRED);
                registry.expireOrderFor(event);
            }
            case ORDER_PLACED -> registry.insertOrder(event);
            case TOKEN_PURCHASED -> {
                registry.closeReservation(event, Registry.ReservationStatus.PURCHASED);
                registry.updateOrder(event, Registry.OrderStatus.PAID);
            }
            case PAYMENT_FAILED -> {
                registry.closeReservation(event, Registry.ReservationStatus.PAYMENT_FAILED);
                registry.updateOrder(event, Registry.OrderStatus.PAYMENT_FAILED);
            }
            case PAYMENT_REFUND_REQUIRED -> registry.updateOrder(event, Registry.OrderStatus.REFUND_REQUIRED);
            case RULE_VERSION_PUBLISHED -> registry.insertRuleSet(event);
            default -> {
                // The rest only move units, or record a decision.
            }
        }
        return legs.size();
    }

    /**
     * The replay test: throw away every projection, rebuild it from the Action Ledger alone (checking the hash
     * chain on the way), and compare with what was there before. One transaction, so a broken chain rolls
     * everything back and leaves the projections as they were.
     */
    @Transactional
    public ReplayReport replay() {
        lockForRebuild();
        Map<String, Long> before = tokenLedger.snapshot();
        Map<String, String> workflowBefore = registry.workflowSnapshot();
        String comparedWith = "the projections as they were before this replay";
        Snapshot wiped = beforeWipe;
        if (wiped != null && before.isEmpty() && workflowBefore.isEmpty()) {
            before = wiped.holdings();
            workflowBefore = wiped.workflow();
            comparedWith = "the projections as they were before the balance tables were deleted";
        }
        truncateProjections();

        long[] count = {0, 0};
        String[] prevHash = {ActionLedger.GENESIS_HASH};
        actions.forEachInOrder(event -> {
            if (!event.prevHash().equals(prevHash[0]) || !event.expectedHash().equals(event.hash())) {
                throw new LedgerIntegrityException("Action Ledger hash chain is broken at seq " + event.seq()
                        + "; replay aborted and projections left unchanged");
            }
            count[1] += apply(event);
            prevHash[0] = event.hash();
            count[0]++;
        });

        Map<String, Long> after = tokenLedger.snapshot();
        beforeWipe = null;
        return new ReplayReport(count[0], count[1], true, comparedWith, before, after, before.equals(after),
                workflowBefore.equals(registry.workflowSnapshot()), diff(before, after));
    }

    /**
     * Demo: deletes every projection (movements, holdings, tokens, reservations, orders, rule versions) and
     * returns the holdings that were there, so the replay can be shown rebuilding them. The Action Ledger is
     * untouched.
     */
    @Transactional
    public Map<String, Long> wipeProjections() {
        lockForRebuild();
        Map<String, Long> before = tokenLedger.snapshot();
        Map<String, String> workflow = registry.workflowSnapshot();
        if (!before.isEmpty() || !workflow.isEmpty()) {
            beforeWipe = new Snapshot(before, workflow);
        }
        truncateProjections();
        return before;
    }

    /** Read-only integrity check: hash chain, holdings vs movements, and zero-sum movements per action. */
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
        boolean holdingsMatch = tokenLedger.snapshot().equals(tokenLedger.sumOfMovements());
        List<Long> unbalanced = tokenLedger.unbalancedActions();
        return new VerifyReport(count[0], broken[0] == null, broken[0], prevHash[0], holdingsMatch, unbalanced,
                broken[0] == null && holdingsMatch && unbalanced.isEmpty());
    }

    /**
     * Writers lock a token row and then the chain; taking the token table first and the chain second keeps the
     * same order, so a rebuild waits for in-flight writes instead of deadlocking with them.
     */
    private void lockForRebuild() {
        jdbc.execute("LOCK TABLE token IN ACCESS EXCLUSIVE MODE");
        actions.lockChain();
    }

    private void truncateProjections() {
        jdbc.execute("TRUNCATE token_movement, holding, reservation, purchase_order, rule_set_version, token"
                + " RESTART IDENTITY");
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
