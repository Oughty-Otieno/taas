package com.taas.rules;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * A rule set: versioned data, not deployed code.
 *
 * <pre>{
 *   "ruleSetId": "loyalty.default", "version": "v7", "effectiveFrom": "2026-11-01T00:00:00Z",
 *   "rules": [
 *     { "id": "tier-high", "when": "event.type == 'TXN_SETTLED' && event.amountKes >= 50000",
 *       "then": { "award": { "tokenId": "LOYALTY_MPR", "units": 100 } }, "priority": 10, "stopOnMatch": true },
 *     { "id": "tier-base", "when": "event.type == 'TXN_SETTLED' && event.amountKes >= 1000",
 *       "then": { "award": { "tokenId": "LOYALTY_MPR", "units": 1 } }, "stopOnMatch": true }
 *   ]
 * }</pre>
 *
 * <p><b>Overlap resolution is part of the contract:</b> rules are evaluated in descending {@code priority}
 * (default 0), ties in document order. Every matching rule adds its award; a match on a rule with
 * {@code stopOnMatch} ends evaluation. {@code units} is an integer or an expression over {@code event}, such as
 * {@code "event.amountKes / 1000"} (integer division).
 *
 * <p>An optional {@code "redemption": { "kesPerUnit": 10 }} sets what one unit is worth when redeemed while this
 * version is in force. Without it, the token's own terms apply. Every redemption records the rate and its source.
 */
public record RuleSet(String ruleSetId, String version, Instant effectiveFrom, String description,
        List<Rule> rules, Redemption redemption) {

    /** The conversion rate for redeeming the units this rule set awards. */
    public record Redemption(long kesPerUnit) {
    }

    public record Rule(String id, String name, String when, String tokenId, String units, int priority,
            boolean stopOnMatch, int position, String createdBy) {
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final int MAX_RULES = 50;

    /** Rules in the order they are evaluated. */
    public List<Rule> evaluationOrder() {
        return rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority).reversed().thenComparingInt(Rule::position))
                .toList();
    }

    /** Parses a document, collecting every problem rather than stopping at the first. */
    public static RuleSet parse(JsonNode doc, List<String> errors) {
        String ruleSetId = text(doc, "ruleSetId");
        String version = text(doc, "version");
        if (ruleSetId == null || !ID.matcher(ruleSetId).matches()) {
            errors.add("ruleSetId must be 1-64 of a-z, 0-9, '.', '_' or '-'");
        }
        if (version == null || !Pattern.matches("[A-Za-z0-9._-]{1,32}", version)) {
            errors.add("version must be 1-32 of A-Z, a-z, 0-9, '.', '_' or '-'");
        }
        Instant effectiveFrom = null;
        String eff = text(doc, "effectiveFrom");
        try {
            effectiveFrom = eff == null ? null : Instant.parse(eff);
        } catch (DateTimeParseException e) {
            // reported below
        }
        if (effectiveFrom == null) {
            errors.add("effectiveFrom must be an ISO-8601 instant, e.g. 2026-12-01T00:00:00Z");
        }
        List<Rule> rules = new ArrayList<>();
        JsonNode list = doc.get("rules");
        if (list == null || !list.isArray() || list.isEmpty()) {
            errors.add("rules must be a non-empty array");
        } else if (list.size() > MAX_RULES) {
            errors.add("at most " + MAX_RULES + " rules per set");
        } else {
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                JsonNode r = list.get(i);
                String where = "rules[" + i + "]";
                String id = text(r, "id");
                if (id == null || !ID.matcher(id).matches()) {
                    errors.add(where + ".id must be 1-64 of a-z, 0-9, '.', '_' or '-'");
                } else if (!ids.add(id)) {
                    errors.add(where + ".id '" + id + "' is used twice");
                }
                String when = text(r, "when");
                if (when == null || when.isBlank()) {
                    errors.add(where + ".when is required");
                }
                JsonNode award = r.path("then").path("award");
                String tokenId = text(award, "tokenId");
                if (tokenId == null) {
                    errors.add(where + ".then.award.tokenId is required");
                }
                JsonNode unitsNode = award.get("units");
                String units = null;
                if (unitsNode == null || unitsNode.isNull()) {
                    errors.add(where + ".then.award.units is required");
                } else if (unitsNode.isIntegralNumber()) {
                    if (unitsNode.asLong() < 0) {
                        errors.add(where + ".then.award.units must not be negative");
                    }
                    units = Long.toString(unitsNode.asLong());
                } else if (unitsNode.isString()) {
                    units = unitsNode.asString();
                } else {
                    errors.add(where + ".then.award.units must be an integer or an expression string");
                }
                JsonNode priority = r.get("priority");
                if (priority != null && !priority.isIntegralNumber()) {
                    errors.add(where + ".priority must be an integer");
                }
                JsonNode stop = r.get("stopOnMatch");
                if (stop != null && !stop.isBoolean()) {
                    errors.add(where + ".stopOnMatch must be true or false");
                }
                rules.add(new Rule(id, text(r, "name"), when, tokenId, units,
                        priority == null ? 0 : priority.asInt(), stop != null && stop.asBoolean(), i,
                        text(r, "createdBy")));
            }
        }
        Redemption redemption = null;
        JsonNode r = doc.get("redemption");
        if (r != null && !r.isNull()) {
            JsonNode rate = r.get("kesPerUnit");
            if (rate == null || !rate.isIntegralNumber() || rate.asLong() <= 0) {
                errors.add("redemption.kesPerUnit must be a whole number of shillings above 0");
            } else {
                redemption = new Redemption(rate.asLong());
            }
        }
        return new RuleSet(ruleSetId, version, effectiveFrom, text(doc, "description"), rules, redemption);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
