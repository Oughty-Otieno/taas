package com.taas.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * Evaluates rule sets. Expressions are SpEL in a locked-down context: read-only map access to {@code event}
 * and nothing else (no types, constructors, beans or method calls), so a published rule cannot run code.
 * Evaluation is deterministic: same rule set, same event, same answer.
 */
@Component
public class RuleEngine {

    /** One rule's verdict, in evaluation order. */
    public record Step(String ruleId, int priority, boolean matched, long units, String tokenId, boolean stopped) {
    }

    public record Evaluation(Map<String, Long> unitsByToken, List<Step> trace) {

        public long totalUnits() {
            return unitsByToken.values().stream().mapToLong(Long::longValue).sum();
        }

        /** Matched rules as "id:units", the form recorded on the event. */
        public String matchedSummary() {
            return String.join(",", trace.stream().filter(Step::matched).map(s -> s.ruleId() + ":" + s.units())
                    .toList());
        }
    }

    private static final int MAX_EXPRESSION_LENGTH = 500;
    private static final long MAX_UNITS_PER_RULE = 1_000_000;

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    public Evaluation evaluate(RuleSet ruleSet, Map<String, Object> event) {
        EvaluationContext context = SimpleEvaluationContext.forPropertyAccessors(new MapAccessor(false))
                .withAssignmentDisabled()
                .withRootObject(Map.of("event", event))
                .build();
        Map<String, Long> units = new LinkedHashMap<>();
        List<Step> trace = new ArrayList<>();
        boolean stopped = false;
        for (RuleSet.Rule rule : ruleSet.evaluationOrder()) {
            if (stopped) {
                trace.add(new Step(rule.id(), rule.priority(), false, 0, rule.tokenId(), false));
                continue;
            }
            boolean matched = Boolean.TRUE.equals(compile(rule.when()).getValue(context, Boolean.class));
            long awarded = matched ? units(rule, context) : 0;
            if (matched && awarded > 0) {
                units.merge(rule.tokenId(), awarded, Long::sum);
            }
            stopped = matched && rule.stopOnMatch();
            trace.add(new Step(rule.id(), rule.priority(), matched, awarded, rule.tokenId(), stopped));
        }
        return new Evaluation(units, trace);
    }

    /** Checks every expression compiles and evaluates against a sample event. Returns the problems found. */
    public List<String> check(RuleSet ruleSet) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> sample = Map.of("type", "TXN_SETTLED", "amountKes", 1000L, "merchantId", "m",
                "accountId", "a", "channel", "POS");
        EvaluationContext context = SimpleEvaluationContext.forPropertyAccessors(new MapAccessor(false))
                .withAssignmentDisabled().withRootObject(Map.of("event", sample)).build();
        for (RuleSet.Rule rule : ruleSet.rules()) {
            try {
                compile(rule.when()).getValue(context, Boolean.class);
            } catch (ExpressionException | IllegalArgumentException e) {
                errors.add("rule '" + rule.id() + "' when: " + e.getMessage());
            }
            try {
                units(rule, context);
            } catch (ExpressionException | IllegalArgumentException e) {
                errors.add("rule '" + rule.id() + "' units: " + e.getMessage());
            }
        }
        return errors;
    }

    private long units(RuleSet.Rule rule, EvaluationContext context) {
        String units = rule.units();
        if (units.matches("\\d+")) {
            return Long.parseLong(units);
        }
        Object value = compile(units).getValue(context);
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException("units must evaluate to a whole number, got " + value);
        }
        long n = ((Number) value).longValue();
        if (n < 0 || n > MAX_UNITS_PER_RULE) {
            throw new IllegalArgumentException("units must be between 0 and " + MAX_UNITS_PER_RULE + ", got " + n);
        }
        return n;
    }

    private Expression compile(String text) {
        if (text == null || text.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("expression must be 1-" + MAX_EXPRESSION_LENGTH + " characters");
        }
        if (cache.size() > 2000) {
            cache.clear();   // simulations can bring arbitrary expressions; keep the cache bounded
        }
        return cache.computeIfAbsent(text, parser::parseExpression);
    }
}
