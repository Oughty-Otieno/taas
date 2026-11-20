package com.taas.rules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.taas.ledger.ActionEvent;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.EventType;

/**
 * Dry-runs a proposed rule set over the qualifying events already in the Action Ledger, and compares each with
 * what was actually awarded. Nothing is written. Because every award recorded its inputs and its rule version,
 * the comparison is exact: "what if customers earned 2 tokens instead of 1 per KES 1,000?" has a precise answer.
 */
@Component
public class RuleSimulator {

    public record Row(long seq, Instant occurredAt, String accountId, long amountKes, String recordedVersion,
            long actualUnits, String actualRules, long simulatedUnits, String simulatedRules) {
    }

    public record AccountDelta(String accountId, long actual, long simulated, long delta) {
    }

    public record Result(String ruleSetId, String proposedVersion, int eventsEvaluated, long actualUnits,
            long simulatedUnits, long deltaUnits, int eventsChanged, List<AccountDelta> byAccount, List<Row> rows,
            boolean truncated) {
    }

    private static final int MAX_EVENTS = 10_000;
    private static final int MAX_ROWS = 300;

    private final ActionLedger actions;
    private final RuleEngine engine;

    public RuleSimulator(ActionLedger actions, RuleEngine engine) {
        this.actions = actions;
        this.engine = engine;
    }

    public Result simulate(RuleSet proposed, Instant from, Instant to) {
        ActionLedger.Filter filter = new ActionLedger.Filter(null, null, null, null,
                List.of(EventType.TOKEN_EARNED.name(), EventType.AWARD_NOT_QUALIFIED.name()), from, to, null);
        List<ActionEvent> events = actions.list(filter, null, MAX_EVENTS + 1, false);
        boolean truncated = events.size() > MAX_EVENTS;
        if (truncated) {
            events = events.subList(0, MAX_EVENTS);
        }
        List<Row> rows = new ArrayList<>();
        Map<String, long[]> byAccount = new TreeMap<>();
        long actualTotal = 0;
        long simulatedTotal = 0;
        int changed = 0;
        int evaluated = 0;
        for (ActionEvent e : events) {
            if (!proposed.ruleSetId().equals(e.opt("ruleSetId"))) {
                continue;
            }
            evaluated++;
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("type", e.get("txnType"));
            input.put("amountKes", e.getLong("amountKes"));
            input.put("merchantId", e.payload().getOrDefault("merchantId", ""));
            input.put("channel", e.payload().getOrDefault("channel", ""));
            input.put("accountId", e.get("accountId"));
            RuleEngine.Evaluation sim = engine.evaluate(proposed, input);
            long actual = e.type() == EventType.TOKEN_EARNED ? e.getLong("units") : 0;
            long simulated = e.type() == EventType.TOKEN_EARNED
                    ? sim.unitsByToken().getOrDefault(e.tokenId(), 0L) : sim.totalUnits();
            actualTotal += actual;
            simulatedTotal += simulated;
            if (actual != simulated) {
                changed++;
            }
            long[] acc = byAccount.computeIfAbsent(e.get("accountId"), k -> new long[2]);
            acc[0] += actual;
            acc[1] += simulated;
            if (rows.size() < MAX_ROWS) {
                rows.add(new Row(e.seq(), e.occurredAt(), e.get("accountId"), e.getLong("amountKes"),
                        e.get("ruleSetVersion"), actual, e.payload().getOrDefault("matched", ""), simulated,
                        sim.matchedSummary()));
            }
        }
        List<AccountDelta> accounts = byAccount.entrySet().stream()
                .map(en -> new AccountDelta(en.getKey(), en.getValue()[0], en.getValue()[1],
                        en.getValue()[1] - en.getValue()[0]))
                .toList();
        return new Result(proposed.ruleSetId(), proposed.version(), evaluated, actualTotal, simulatedTotal,
                simulatedTotal - actualTotal, changed, accounts, rows, truncated || rows.size() < evaluated);
    }
}
