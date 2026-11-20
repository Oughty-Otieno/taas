package com.taas.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.taas.common.InvalidRequest;
import com.taas.common.NotFoundException;
import com.taas.common.Rejection;
import com.taas.ledger.Accounts;
import com.taas.ledger.ActionEvent;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.EventType;
import com.taas.ledger.Registry;
import com.taas.ledger.Registry.ReservationStatus;
import com.taas.ledger.Registry.TokenDef;
import com.taas.ledger.Registry.TokenKind;
import com.taas.ledger.TokenLedger;
import com.taas.policy.PolicyDecisionPoint;
import com.taas.rules.RuleEngine;
import com.taas.rules.RuleSet;
import com.taas.workflow.CommandRunner.Caller;
import com.taas.workflow.CommandRunner.Command;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Workflow Engine: decides what is allowed and what is recorded. It never writes a balance and never reads
 * one to decide: invariants (overdraft, supply ceiling, the ownership cap) are the database's, checked on the
 * movements this engine records. Each write locks its token row, so writes to one token are serialised while
 * writes to different tokens proceed in parallel.
 *
 * <p>Reservation expiry and payment failure are paths here, not exceptions: each records a compensating event.
 */
@Service
public class WorkflowEngine {

    private static final String TIMER_EXPIRY = "timer:reservation-expiry";
    private static final String TIMER_INACTIVITY = "timer:loyalty-inactivity";

    private final CommandRunner runner;
    private final Registry registry;
    private final TokenLedger tokenLedger;
    private final ActionLedger actions;
    private final PolicyDecisionPoint pdp;
    private final RuleEngine rules;
    private final ObjectMapper json;
    private final String defaultRuleSet;

    public WorkflowEngine(CommandRunner runner, Registry registry, TokenLedger tokenLedger, ActionLedger actions,
            PolicyDecisionPoint pdp, RuleEngine rules, ObjectMapper json,
            @Value("${taas.rules.default-loyalty-rule-set}") String defaultRuleSet) {
        this.runner = runner;
        this.registry = registry;
        this.tokenLedger = tokenLedger;
        this.actions = actions;
        this.pdp = pdp;
        this.rules = rules;
        this.json = json;
        this.defaultRuleSet = defaultRuleSet;
    }

    // --- Catalogue and supply --------------------------------------------------------------------

    public Reply defineToken(Caller caller, Commands.DefineToken c) {
        boolean asset = "ASSET".equals(c.kind());
        if (asset && c.unitPriceKes() == null) {
            throw new InvalidRequest("An ASSET token needs unitPriceKes");
        }
        Command cmd = new Command("define-token", c.tokenId(), null, c);
        return runner.execute(caller, cmd, null, tx -> {
            actions.lockChain();   // serialises definitions, so two cannot race to the same id
            if (registry.token(c.tokenId()).isPresent()) {
                throw Rejection.conflict("TOKEN_EXISTS", "Token " + c.tokenId() + " is already defined");
            }
            Integer percent = c.maxHolderPercent() != null ? c.maxHolderPercent() : asset ? Integer.valueOf(20) : null;
            SortedMap<String, String> p = fields(
                    "name", c.name(), "kind", c.kind(), "assetType", c.assetType(), "backing", c.backing(),
                    "valuationKes", c.valuationKes(), "unitPriceKes", c.unitPriceKes(),
                    "redeemValueKes", c.redeemValueKes(), "authorisedSupply", c.authorisedSupply(),
                    "maxHolderBps", percent == null ? null : percent * 100,
                    "reservationMinutes", c.reservationMinutes() == null ? 15 : c.reservationMinutes(),
                    "kycRequired", c.kycRequired() != null ? c.kycRequired() : asset,
                    "inactivityMonths", asset ? null : c.inactivityMonths(),
                    "ruleSetId", asset ? null : c.ruleSetId() != null ? c.ruleSetId() : defaultRuleSet,
                    "policyVersion", "v1");
            ActionEvent e = tx.record(EventType.TOKEN_DEFINED, c.tokenId(), c.tokenId(),
                    "policy:" + c.tokenId() + "@v1", p);
            return Reply.of(201, body("actionId", e.id(), "seq", e.seq(), "tokenId", c.tokenId()));
        });
    }

    public Reply issue(Caller caller, Commands.Issue c) {
        TokenDef token = requireToken(c.tokenId());
        Command cmd = new Command("issue", c.tokenId(), c.tokenId(), c);
        return runner.execute(caller, cmd, null, tx -> {
            lock(token.tokenId());
            ActionEvent e = tx.record(EventType.TOKEN_ISSUED, token.tokenId(), token.tokenId(),
                    token.policyRuleVersion(), fields("units", c.units(), "reference", c.reference()));
            return Reply.of(201, body("actionId", e.id(), "seq", e.seq(), "tokenId", token.tokenId(), "units",
                    c.units(), "supply", tokenLedger.supply(token.tokenId(), token.authorisedSupply())));
        });
    }

    // --- Rules -----------------------------------------------------------------------------------

    public Reply publishRules(Caller caller, Commands.PublishRules c) {
        RuleSet ruleSet = parseRuleSet(c.document());
        Command cmd = new Command("publish-rules", ruleSet.ruleSetId(), null, c);
        return runner.execute(caller, cmd, null, tx -> {
            actions.lockChain();
            if (registry.ruleSetVersion(ruleSet.ruleSetId(), ruleSet.version()).isPresent()) {
                throw Rejection.conflict("VERSION_EXISTS", ruleSet.ruleSetId() + "@" + ruleSet.version()
                        + " is already published; rule versions are immutable, publish a new one");
            }
            ActionEvent e = tx.record(EventType.RULE_VERSION_PUBLISHED, ruleSet.ruleSetId(), null,
                    ruleSet.ruleSetId() + "@" + ruleSet.version(),
                    fields("version", ruleSet.version(), "effectiveFrom", ruleSet.effectiveFrom(),
                            "document", json.writeValueAsString(c.document()), "ruleCount", ruleSet.rules().size(),
                            "description", ruleSet.description()));
            return Reply.of(201, body("actionId", e.id(), "seq", e.seq(), "ruleSetId", ruleSet.ruleSetId(),
                    "version", ruleSet.version(), "effectiveFrom", ruleSet.effectiveFrom(),
                    "inForceNow", !ruleSet.effectiveFrom().isAfter(tx.now())));
        });
    }

    /** Parses and checks a rule-set document: structure, expressions, and that its tokens exist. */
    public RuleSet parseRuleSet(JsonNode document) {
        List<String> errors = new ArrayList<>();
        RuleSet ruleSet = RuleSet.parse(document, errors);
        if (errors.isEmpty()) {
            errors.addAll(rules.check(ruleSet));
            for (RuleSet.Rule r : ruleSet.rules()) {
                TokenDef t = registry.token(r.tokenId()).orElse(null);
                if (t == null) {
                    errors.add("rule '" + r.id() + "' awards unknown token " + r.tokenId());
                } else if (t.kind() != TokenKind.LOYALTY) {
                    errors.add("rule '" + r.id() + "' awards " + r.tokenId() + ", which is not a loyalty token");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequest("The rule set is not valid", errors);
        }
        return ruleSet;
    }

    // --- Loyalty ---------------------------------------------------------------------------------

    /** The client submits a transaction, never an amount: the active rule set decides what is earned. */
    public Reply award(Caller caller, Commands.Award c) {
        requireHolder(c.accountId());
        String ruleSetId = c.ruleSetId() == null ? defaultRuleSet : c.ruleSetId();
        Command cmd = new Command("award", c.accountId(), null, c);
        return runner.execute(caller, cmd, null, tx -> {
            Registry.RuleSetVersion version = registry.activeRuleSet(ruleSetId, tx.now()).orElseThrow(() ->
                    Rejection.rule("NO_ACTIVE_RULES", "Rule set " + ruleSetId + " has no version in force"));
            RuleSet ruleSet = RuleSet.parse(json.readTree(version.document()), new ArrayList<>());
            Commands.Transaction t = c.transaction();
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", t.type());
            event.put("amountKes", t.amountKes());
            event.put("merchantId", t.merchantId() == null ? "" : t.merchantId());
            event.put("channel", t.channel() == null ? "" : t.channel());
            event.put("accountId", c.accountId());
            RuleEngine.Evaluation result = rules.evaluate(ruleSet, event);
            String ruleVersion = ruleSetId + "@" + version.version();
            SortedMap<String, String> base = fields("accountId", c.accountId(), "txnType", t.type(),
                    "amountKes", t.amountKes(), "merchantId", t.merchantId(), "channel", t.channel(),
                    "reference", t.reference(), "ruleSetId", ruleSetId, "ruleSetVersion", version.version(),
                    "matched", result.matchedSummary());

            List<Object> actionIds = new ArrayList<>();
            if (result.unitsByToken().isEmpty()) {
                ActionEvent e = tx.record(EventType.AWARD_NOT_QUALIFIED, c.accountId(), null, ruleVersion, base);
                return Reply.of(200, body("actionId", e.id(), "seq", e.seq(), "awarded", Map.of(),
                        "ruleSet", ruleVersion, "trace", result.trace(),
                        "detail", "No rule in " + ruleVersion + " awards units for this transaction"));
            }
            List<TokenLedger.Holding> balances = new ArrayList<>();
            for (String tokenId : new TreeSet<>(result.unitsByToken().keySet())) {
                TokenDef token = lock(tokenId);
                SortedMap<String, String> p = new TreeMap<>(base);
                p.put("units", Long.toString(result.unitsByToken().get(tokenId)));
                ActionEvent e = tx.record(EventType.TOKEN_EARNED, token.tokenId(), token.tokenId(), ruleVersion, p);
                actionIds.add(e.id());
                balances.add(tokenLedger.holding(tokenId, c.accountId()));
            }
            return Reply.of(201, body("actionId", actionIds.get(0), "actionIds", actionIds,
                    "awarded", result.unitsByToken(), "ruleSet", ruleVersion, "trace", result.trace(),
                    "balance", balances));
        });
    }

    // --- Asset purchase --------------------------------------------------------------------------

    public Reply reserve(Caller caller, Commands.Reserve c) {
        TokenDef token = requireToken(c.tokenId());
        requireHolder(c.accountId());
        if (token.kind() != TokenKind.ASSET) {
            throw new InvalidRequest(token.tokenId() + " is a loyalty token; only asset tokens are reserved");
        }
        Command cmd = new Command("reserve", token.tokenId(), token.tokenId(), c);
        long bidKes = Math.multiplyExact(c.units(), token.unitPriceKes());
        Runnable policy = () -> {
            pdp.requireActingFor(caller, c.accountId());
            eligibility(token, c.accountId(), bidKes);
        };
        return runner.execute(caller, cmd, policy, tx -> {
            lock(token.tokenId());
            policy.run();   // re-checked at commit
            long maxHold = token.reservationMinutes() * 60L;
            long hold = c.holdSeconds() == null ? maxHold : Math.min(c.holdSeconds(), maxHold);
            Instant expiresAt = tx.now().plusSeconds(hold);
            long amountKes = Math.multiplyExact(c.units(), token.unitPriceKes());
            UUID reservationId = UUID.randomUUID();
            // The cap and the remaining supply are not checked here: the database refuses the movement.
            ActionEvent e = tx.record(EventType.TOKEN_RESERVED, token.tokenId(), token.tokenId(),
                    token.policyRuleVersion(), fields("reservationId", reservationId, "accountId", c.accountId(),
                            "units", c.units(), "unitPriceKes", token.unitPriceKes(), "amountKes", amountKes,
                            "expiresAt", expiresAt, "holdSeconds", hold));
            return Reply.of(201, body("actionId", e.id(), "seq", e.seq(), "reservationId", reservationId,
                    "tokenId", token.tokenId(), "accountId", c.accountId(), "units", c.units(),
                    "amountKes", amountKes, "expiresAt", expiresAt,
                    "holding", tokenLedger.holding(token.tokenId(), c.accountId())));
        });
    }

    public Reply release(Caller caller, UUID reservationId) {
        Registry.Reservation found = requireReservation(reservationId);
        Command cmd = new Command("release", found.tokenId(), found.tokenId(), Map.of("reservationId", reservationId));
        return runner.execute(caller, cmd, () -> pdp.requireActingFor(caller, found.accountId()), tx -> {
            TokenDef token = lock(found.tokenId());
            Registry.Reservation r = requireReservation(reservationId);
            requireHeld(r);
            registry.orderForReservation(reservationId).ifPresent(o -> {
                if (o.status() == Registry.OrderStatus.AWAITING_PAYMENT) {
                    throw Rejection.conflict("ORDER_AWAITING_PAYMENT", "Reservation " + reservationId
                            + " has an order awaiting payment; it is released by payment failure or expiry");
                }
            });
            ActionEvent e = tx.record(EventType.TOKEN_RESERVATION_RELEASED, token.tokenId(), token.tokenId(),
                    token.policyRuleVersion(), fields("reservationId", reservationId, "accountId", r.accountId(),
                            "units", r.units(), "reason", "Released by the client"));
            return Reply.of(200, body("actionId", e.id(), "seq", e.seq(), "reservationId", reservationId,
                    "status", ReservationStatus.RELEASED));
        });
    }

    /** Confirms a reserved bid. Payment is charged downstream of the commit, from the outbox. */
    public Reply placeOrder(Caller caller, Commands.PlaceOrder c) {
        Registry.Reservation found = requireReservation(c.reservationId());
        Command cmd = new Command("order", found.tokenId(), found.tokenId(), c);
        return runner.execute(caller, cmd, () -> pdp.requireActingFor(caller, found.accountId()), tx -> {
            TokenDef token = lock(found.tokenId());
            Registry.Reservation r = requireReservation(c.reservationId());
            requireHeld(r);
            if (!tx.now().isBefore(r.expiresAt())) {
                throw Rejection.conflict("RESERVATION_EXPIRED", "Reservation " + r.reservationId() + " expired at "
                        + r.expiresAt());
            }
            if (registry.orderForReservation(r.reservationId()).isPresent()) {
                throw Rejection.conflict("ORDER_EXISTS", "Reservation " + r.reservationId() + " already has an order");
            }
            UUID orderId = UUID.randomUUID();
            ActionEvent e = tx.record(EventType.ORDER_PLACED, token.tokenId(), token.tokenId(),
                    token.policyRuleVersion(), fields("orderId", orderId, "reservationId", r.reservationId(),
                            "accountId", r.accountId(), "units", r.units(), "amountKes", r.amountKes(),
                            "paymentMethod", c.paymentMethod() == null ? "MPESA" : c.paymentMethod(),
                            "simulateOutcome", c.simulateOutcome(), "expiresAt", r.expiresAt()));
            return Reply.of(202, body("actionId", e.id(), "seq", e.seq(), "orderId", orderId,
                    "status", Registry.OrderStatus.AWAITING_PAYMENT, "reservationId", r.reservationId(),
                    "amountKes", r.amountKes(), "expiresAt", r.expiresAt()));
        });
    }

    /** The PSP's verdict. Keyed by its event id, so a redelivered callback changes nothing. */
    public Reply paymentCallback(Commands.PaymentCallback c) {
        Registry.Order found = registry.order(c.orderId())
                .orElseThrow(() -> new NotFoundException("Order " + c.orderId() + " not found"));
        Caller caller = new Caller("psp", "psp:" + c.pspEventId());
        Command cmd = new Command("payment-callback", found.tokenId(), found.tokenId(), c);
        return runner.execute(caller, cmd, null, tx -> {
            TokenDef token = lock(found.tokenId());
            Registry.Order order = registry.order(c.orderId()).orElseThrow();
            if (order.status() != Registry.OrderStatus.AWAITING_PAYMENT
                    && order.status() != Registry.OrderStatus.EXPIRED) {
                throw Rejection.conflict("ORDER_ALREADY_SETTLED", "Order " + order.orderId() + " is already "
                        + order.status());
            }
            Registry.Reservation r = requireReservation(order.reservationId());
            String policy = token.policyRuleVersion();
            SortedMap<String, String> base = fields("orderId", order.orderId(), "reservationId", r.reservationId(),
                    "accountId", order.accountId(), "units", order.units(), "amountKes", order.amountKes(),
                    "pspEventId", c.pspEventId(), "pspReference", c.pspReference(), "paidKes", c.amountKes());
            List<Object> recorded = new ArrayList<>();

            if ("FAILED".equals(c.status())) {
                if (r.status() == ReservationStatus.HELD) {
                    SortedMap<String, String> p = new TreeMap<>(base);
                    p.put("reason", c.reason() == null ? "Declined by the payment provider" : c.reason());
                    recorded.add(tx.record(EventType.PAYMENT_FAILED, token.tokenId(), token.tokenId(), policy, p).id());
                }
            } else {
                recorded.add(tx.record(EventType.PAYMENT_RECEIVED, token.tokenId(), token.tokenId(), policy, base).id());
                boolean live = r.status() == ReservationStatus.HELD && tx.now().isBefore(r.expiresAt());
                if (c.amountKes() != order.amountKes()) {
                    recorded.add(refund(tx, token, base, "AMOUNT_MISMATCH: paid " + c.amountKes() + ", owed "
                            + order.amountKes()));
                } else if (live) {
                    recorded.add(tx.record(EventType.TOKEN_PURCHASED, token.tokenId(), token.tokenId(), policy,
                            base).id());
                } else {
                    if (r.status() == ReservationStatus.HELD) {
                        // The hold ran out before the payment arrived: the timer wins, whether or not it has run.
                        recorded.add(tx.record(EventType.TOKEN_RESERVATION_EXPIRED, token.tokenId(), token.tokenId(),
                                policy, fields("reservationId", r.reservationId(), "accountId", r.accountId(),
                                        "units", r.units(), "expiresAt", r.expiresAt(), "orderId", order.orderId()))
                                .id());
                    }
                    recorded.add(refund(tx, token, base, "Payment arrived after the reservation "
                            + (r.status() == ReservationStatus.HELD ? "expired" : "was " + r.status())));
                }
            }
            Registry.Order now = registry.order(c.orderId()).orElseThrow();
            return Reply.of(200, body("orderId", now.orderId(), "orderStatus", now.status(), "actionIds", recorded));
        });
    }

    private UUID refund(CommandRunner.Tx tx, TokenDef token, SortedMap<String, String> base, String reason) {
        SortedMap<String, String> p = new TreeMap<>(base);
        p.put("reason", reason);
        return tx.record(EventType.PAYMENT_REFUND_REQUIRED, token.tokenId(), token.tokenId(),
                token.policyRuleVersion(), p).id();
    }

    /** Timer: returns an unpaid reservation's units once its hold has run out. */
    public Reply expire(UUID reservationId) {
        Registry.Reservation found = requireReservation(reservationId);
        Caller caller = new Caller(TIMER_EXPIRY, "expire:" + reservationId);
        Command cmd = new Command("expire", found.tokenId(), found.tokenId(), Map.of("reservationId", reservationId));
        return runner.execute(caller, cmd, null, tx -> {
            TokenDef token = lock(found.tokenId());
            Registry.Reservation r = requireReservation(reservationId);
            if (r.status() != ReservationStatus.HELD) {
                throw Rejection.conflict("NOTHING_TO_EXPIRE", "Reservation " + reservationId + " is " + r.status());
            }
            if (tx.now().isBefore(r.expiresAt())) {
                throw Rejection.conflict("NOT_YET_DUE", "Reservation " + reservationId + " runs until " + r.expiresAt());
            }
            SortedMap<String, String> p = fields("reservationId", reservationId, "accountId", r.accountId(),
                    "units", r.units(), "expiresAt", r.expiresAt());
            registry.orderForReservation(reservationId).ifPresent(o -> p.put("orderId", o.orderId().toString()));
            ActionEvent e = tx.record(EventType.TOKEN_RESERVATION_EXPIRED, token.tokenId(), token.tokenId(),
                    token.policyRuleVersion(), p);
            return Reply.of(200, body("actionId", e.id(), "reservationId", reservationId, "status", "EXPIRED"));
        }, false);
    }

    // --- Transfers and redemptions ---------------------------------------------------------------

    public Reply transfer(Caller caller, Commands.Transfer c) {
        TokenDef token = requireToken(c.tokenId());
        requireHolder(c.fromAccountId());
        requireHolder(c.toAccountId());
        if (c.fromAccountId().equals(c.toAccountId())) {
            throw new InvalidRequest("fromAccountId and toAccountId must differ");
        }
        Command cmd = new Command("transfer", token.tokenId(), token.tokenId(), c);
        long valueKes = token.unitPriceKes() == null ? 0 : Math.multiplyExact(c.units(), token.unitPriceKes());
        Runnable policy = () -> {
            pdp.requireActingFor(caller, c.fromAccountId());
            pdp.requireNotFrozen(c.fromAccountId());
            eligibility(token, c.toAccountId(), valueKes);
            pdp.requireWithinVelocity(c.fromAccountId());
        };
        return runner.execute(caller, cmd, policy, tx -> {
            lock(token.tokenId());
            policy.run();   // re-checked at commit
            ActionEvent e = tx.record(EventType.TOKEN_TRANSFERRED, token.tokenId(), token.tokenId(),
                    token.policyRuleVersion(), fields("fromAccountId", c.fromAccountId(), "toAccountId",
                            c.toAccountId(), "units", c.units(), "reference", c.reference()));
            return Reply.of(201, body("actionId", e.id(), "seq", e.seq(),
                    "from", tokenLedger.holding(token.tokenId(), c.fromAccountId()),
                    "to", tokenLedger.holding(token.tokenId(), c.toAccountId())));
        });
    }

    public Reply redeem(Caller caller, Commands.Redeem c) {
        TokenDef token = requireToken(c.tokenId());
        requireHolder(c.accountId());
        Command cmd = new Command("redeem", token.tokenId(), token.tokenId(), c);
        return runner.execute(caller, cmd, () -> pdp.requireActingFor(caller, c.accountId()), tx -> {
            lock(token.tokenId());
            Long perUnit = token.kind() == TokenKind.LOYALTY ? token.redeemValueKes() : token.unitPriceKes();
            long valueKes = perUnit == null ? 0 : Math.multiplyExact(perUnit, c.units());
            ActionEvent e = tx.record(EventType.TOKEN_REDEEMED, token.tokenId(), token.tokenId(),
                    token.policyRuleVersion(), fields("accountId", c.accountId(), "units", c.units(),
                            "merchantId", c.merchantId(), "reference", c.reference(), "valueKes", valueKes));
            return Reply.of(201, body("actionId", e.id(), "seq", e.seq(), "valueKes", valueKes,
                    "holding", tokenLedger.holding(token.tokenId(), c.accountId())));
        });
    }

    /**
     * Timer: lapses a loyalty holder's units after a period with no movement. Reads the holding to know how many
     * units to revoke; that is safe because the token row lock serialises every write to this token.
     */
    public Reply revokeInactive(String tokenId, String accountId, Instant lastActivity) {
        TokenDef found = requireToken(tokenId);
        Caller caller = new Caller(TIMER_INACTIVITY, "revoke:" + tokenId + ":" + accountId + ":" + lastActivity);
        Command cmd = new Command("revoke", tokenId, tokenId, Map.of("accountId", accountId));
        return runner.execute(caller, cmd, null, tx -> {
            lock(tokenId);
            long units = tokenLedger.holding(tokenId, accountId).available();
            if (units <= 0) {
                throw Rejection.conflict("NOTHING_TO_REVOKE", accountId + " holds no " + tokenId);
            }
            ActionEvent e = tx.record(EventType.TOKEN_REVOKED, tokenId, tokenId, found.policyRuleVersion(),
                    fields("accountId", accountId, "units", units, "inactiveSince", lastActivity,
                            "inactivityMonths", found.inactivityMonths(), "reason", "Inactivity"));
            return Reply.of(200, body("actionId", e.id(), "units", units));
        }, false);
    }

    // ---------------------------------------------------------------------------------------------

    private void eligibility(TokenDef token, String accountId, long amountKes) {
        if (token.kycRequired()) {
            pdp.requireKyc(accountId, token.tokenId(), amountKes);
        } else {
            pdp.requireNotFrozen(accountId);
        }
    }

    private TokenDef requireToken(String tokenId) {
        return registry.token(tokenId).orElseThrow(() -> new NotFoundException("Token " + tokenId + " not found"));
    }

    private TokenDef lock(String tokenId) {
        return registry.lockToken(tokenId).orElseThrow(() -> new NotFoundException("Token " + tokenId + " not found"));
    }

    private Registry.Reservation requireReservation(UUID id) {
        return registry.reservation(id).orElseThrow(() -> new NotFoundException("Reservation " + id + " not found"));
    }

    private static void requireHeld(Registry.Reservation r) {
        if (r.status() != ReservationStatus.HELD) {
            throw Rejection.conflict("RESERVATION_" + r.status(), "Reservation " + r.reservationId() + " is "
                    + r.status().name().toLowerCase().replace('_', ' '));
        }
    }

    private static void requireHolder(String account) {
        if (!Accounts.isHolder(account)) {
            throw new InvalidRequest("'" + account + "' is not a valid account id (1-64 of A-Z, a-z, 0-9, '.', '_'"
                    + " or '-', starting with a letter or digit)");
        }
    }

    /** A payload from key/value pairs, leaving out nulls. */
    static SortedMap<String, String> fields(Object... pairs) {
        SortedMap<String, String> out = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                out.put((String) pairs[i], pairs[i + 1].toString());
            }
        }
        return out;
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }
}
