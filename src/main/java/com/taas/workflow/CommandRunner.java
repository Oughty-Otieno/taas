package com.taas.workflow;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.taas.common.Hashes;
import com.taas.common.Rejection;
import com.taas.ledger.ActionEvent;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.EventType;
import com.taas.ledger.Projector;
import com.taas.ledger.Registry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs every write the same way:
 *
 * <ol>
 *   <li>A key already answered is answered from the store: the workflow never runs a second time.</li>
 *   <li>The policy decision point is asked first; a deny never reaches the workflow.</li>
 *   <li>One transaction: lock the key, decide, append events, write movements (the database checks the
 *       invariants), store the reply. The outbox rows go in with the events.</li>
 *   <li>A rejection rolls that transaction back and is then recorded on its own as {@code REQUEST_REJECTED},
 *       with its reply stored, so a retry gets the same refusal.</li>
 * </ol>
 */
@Component
public class CommandRunner {

    /**
     * Who is asking and under which key. Timers and the PSP are clients too. {@code boundAccount} is set when the
     * caller is a customer: they may act for that account and no other.
     */
    public record Caller(String clientId, String idempotencyKey, String boundAccount) {

        public Caller(String clientId, String idempotencyKey) {
            this(clientId, idempotencyKey, null);
        }
    }

    /** One command: what it is, what it touches, and the request its key is bound to. */
    public record Command(String operation, String subjectId, String tokenId, Object request) {
    }

    /** The part of a command that decides and records. */
    @FunctionalInterface
    public interface Work {
        Reply run(Tx tx);
    }

    /** What a command's work may do inside the transaction. */
    public final class Tx {
        private final Caller caller;
        private final Instant now;

        private Tx(Caller caller, Instant now) {
            this.caller = caller;
            this.now = now;
        }

        public Instant now() {
            return now;
        }

        public Caller caller() {
            return caller;
        }

        /** Appends the event to the Action Ledger and projects it, in this transaction. */
        public ActionEvent record(EventType type, String subjectId, String tokenId, String ruleVersion,
                SortedMap<String, String> payload) {
            ActionEvent event = actions.append(new ActionLedger.NewEvent(type, subjectId, tokenId, ruleVersion,
                    payload), caller.clientId(), caller.idempotencyKey(), now);
            projector.apply(event);
            return event;
        }
    }

    private static final Pattern FAILING_ROW = Pattern.compile("Failing row contains \\(([^,]*), ([^,]*),");

    private final IdempotencyStore store;
    private final ActionLedger actions;
    private final Projector projector;
    private final Registry registry;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public CommandRunner(IdempotencyStore store, ActionLedger actions, Projector projector, Registry registry,
            PlatformTransactionManager txManager, ObjectMapper json, Clock clock) {
        this.store = store;
        this.actions = actions;
        this.projector = projector;
        this.registry = registry;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.clock = clock;
    }

    public Reply execute(Caller caller, Command command, Runnable policyCheck, Work work) {
        return execute(caller, command, policyCheck, work, true);
    }

    /**
     * @param recordRejections false for timers: a timer that finds nothing to do is not an event worth keeping
     */
    public Reply execute(Caller caller, Command command, Runnable policyCheck, Work work, boolean recordRejections) {
        String requestHash = Hashes.sha256Hex(command.operation() + "\n" + json.writeValueAsString(command.request()));

        Optional<Reply> earlier = answered(caller, command, requestHash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        try {
            if (policyCheck != null) {
                policyCheck.run();
            }
            return tx.execute(status -> {
                store.lock(caller.clientId(), caller.idempotencyKey());
                Optional<Reply> raced = answered(caller, command, requestHash);
                if (raced.isPresent()) {
                    return raced.get();
                }
                Instant now = clock.instant();
                Reply reply = work.run(new Tx(caller, now));
                store.save(caller.clientId(), caller.idempotencyKey(), command.operation(), requestHash,
                        reply.status(), reply.body(), now);
                return reply;
            });
        } catch (Rejection r) {
            return reject(caller, command, requestHash, r, recordRejections);
        } catch (DataIntegrityViolationException e) {
            Rejection r = translate(e, command);
            if (r == null) {
                throw e;
            }
            return reject(caller, command, requestHash, r, recordRejections);
        }
    }

    private Optional<Reply> answered(Caller caller, Command command, String requestHash) {
        return store.find(caller.clientId(), caller.idempotencyKey()).map(stored -> {
            if (!stored.requestHash().equals(requestHash) || !stored.operation().equals(command.operation())) {
                throw new IdempotencyConflict(caller.idempotencyKey());
            }
            return new Reply(stored.status(), stored.response(), true);
        });
    }

    private Reply reject(Caller caller, Command command, String requestHash, Rejection r, boolean record) {
        if (!record) {
            return Reply.of(r.status().value(), problem(r, null));
        }
        return tx.execute(status -> {
            store.lock(caller.clientId(), caller.idempotencyKey());
            Optional<Reply> raced = answered(caller, command, requestHash);
            if (raced.isPresent()) {
                return raced.get();
            }
            Instant now = clock.instant();
            SortedMap<String, String> payload = new TreeMap<>();
            payload.put("operation", command.operation());
            payload.put("code", r.code());
            payload.put("reason", r.getMessage());
            payload.put("status", Integer.toString(r.status().value()));
            flatten(command.request()).forEach((k, v) -> payload.putIfAbsent(k, v));
            String ruleVersion = command.tokenId() == null ? "platform@v1"
                    : registry.token(command.tokenId()).map(Registry.TokenDef::policyRuleVersion)
                            .orElse("platform@v1");
            String subject = command.subjectId() != null ? command.subjectId() : "platform";
            ActionEvent event = new Tx(caller, now).record(EventType.REQUEST_REJECTED, subject, command.tokenId(),
                    ruleVersion, payload);
            Map<String, Object> body = problem(r, event);
            store.save(caller.clientId(), caller.idempotencyKey(), command.operation(), requestHash,
                    r.status().value(), body, now);
            return Reply.of(r.status().value(), body);
        });
    }

    private static Map<String, Object> problem(Rejection r, ActionEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Rejected");
        body.put("status", r.status().value());
        body.put("code", r.code());
        body.put("detail", r.getMessage());
        if (event != null) {
            body.put("actionId", event.id());
            body.put("seq", event.seq());
        }
        return body;
    }

    /** Scalar request fields as strings, so a rejection records what was asked for. */
    private Map<String, String> flatten(Object request) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode node = json.valueToTree(request);
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (e.getValue().isValueNode() && !e.getValue().isNull()) {
                    out.put(e.getKey(), e.getValue().asString());
                } else if (e.getValue().isObject()) {
                    for (Map.Entry<String, JsonNode> inner : e.getValue().properties()) {
                        if (inner.getValue().isValueNode() && !inner.getValue().isNull()) {
                            out.put(inner.getKey(), inner.getValue().asString());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Turns a database invariant violation into a business rejection naming the invariant. */
    private static Rejection translate(DataIntegrityViolationException e, Command command) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException p && p.getServerErrorMessage() != null) {
                ServerErrorMessage m = p.getServerErrorMessage();
                String constraint = m.getConstraint();
                if (constraint == null) {
                    return null;
                }
                String account = "an account";
                Matcher row = FAILING_ROW.matcher(m.getDetail() == null ? "" : m.getDetail());
                if (row.find()) {
                    account = row.group(2);
                }
                String token = command.tokenId();
                return switch (constraint) {
                    case "holding_ownership_cap" -> Rejection.rule("OWNERSHIP_CAP", "Rejected by the database: "
                            + account + " would hold more than the ownership cap of " + token);
                    case "holding_supply_ceiling" -> Rejection.rule("SUPPLY_CEILING",
                            "Rejected by the database: issuing would exceed the authorised supply of " + token);
                    case "holding_no_overdraft", "holding_reserved_non_negative" -> account.equals("@treasury")
                            ? Rejection.rule("SOLD_OUT", "Rejected by the database: not enough " + token
                                    + " units left in the treasury")
                            : Rejection.rule("INSUFFICIENT_UNITS", "Rejected by the database: " + account
                                    + " does not have enough " + token + " units");
                    default -> null;
                };
            }
        }
        return null;
    }

    /** The same key arrived with a different request. Answered with 409 and never recorded. */
    public static class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict(String key) {
            super("Idempotency-Key '" + key + "' was already used for a different request");
        }
    }

    static Rejection forbidden(String code, String message) {
        return new Rejection(HttpStatus.FORBIDDEN, code, message);
    }
}
