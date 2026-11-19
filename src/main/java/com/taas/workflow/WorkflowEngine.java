package com.taas.workflow;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taas.common.BusinessRuleException;
import com.taas.common.IdempotencyConflictException;
import com.taas.common.NotFoundException;
import com.taas.ledger.Accounts;
import com.taas.ledger.ActionEvent;
import com.taas.ledger.ActionLedger;
import com.taas.ledger.EventType;
import com.taas.ledger.Hashes;
import com.taas.ledger.Projector;
import com.taas.ledger.Registry;
import com.taas.ledger.Registry.ReservationStatus;
import com.taas.ledger.Registry.TokenDef;
import com.taas.ledger.TokenLedger;

/**
 * The Workflow Engine: decides whether an operation is allowed and what event it produces. It never
 * writes a balance. Each operation runs in one transaction that (1) takes the ledger lock, (2) settles
 * idempotency on the txId, (3) checks the rules against current state, (4) appends the event to the
 * Action Ledger and (5) projects it into the Token Ledger. Either all of it commits or none of it does.
 */
@Service
public class WorkflowEngine {

    /** Outcome of a command: the event it recorded, and whether this call was a repeat of an earlier one. */
    public record Result(ActionEvent event, boolean duplicate) {
    }

    private record Decision(EventType type, SortedMap<String, String> payload) {
    }

    private static final Pattern TOKEN_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,31}");
    private static final int DEFAULT_RESERVATION_MINUTES = 15;

    private final ActionLedger actions;
    private final TokenLedger tokenLedger;
    private final Registry registry;
    private final Projector projector;
    private final Clock clock;

    public WorkflowEngine(ActionLedger actions, TokenLedger tokenLedger, Registry registry, Projector projector,
            Clock clock) {
        this.actions = actions;
        this.tokenLedger = tokenLedger;
        this.registry = registry;
        this.projector = projector;
        this.clock = clock;
    }

    @Transactional
    public Result createToken(Commands.CreateToken c) {
        String code = c.code().trim().toUpperCase();
        SortedMap<String, String> request = fields("code", code, "name", c.name(), "kind", c.kind(),
                "kesPerToken", c.kesPerToken(), "reservationMinutes", c.reservationMinutes(),
                "maxSupply", c.maxSupply());
        return run(c.txId(), "create", code, request, () -> {
            if (!TOKEN_CODE.matcher(code).matches()) {
                throw new BusinessRuleException("Token code must be 2-32 of A-Z, 0-9, '_' or '-'");
            }
            if (registry.token(code).isPresent()) {
                throw new BusinessRuleException("Token " + code + " already exists");
            }
            Registry.TokenKind kind = parseKind(c.kind());
            int minutes = c.reservationMinutes() == null ? DEFAULT_RESERVATION_MINUTES : c.reservationMinutes();
            return new Decision(EventType.TOKEN_CREATED, fields("name", c.name().trim(), "kind", kind.name(),
                    "kesPerToken", c.kesPerToken(), "reservationMinutes", minutes, "maxSupply", c.maxSupply()));
        });
    }

    @Transactional
    public Result issue(String code, Commands.Issue c) {
        SortedMap<String, String> request = fields("to", c.to(), "amount", c.amount(), "reference", c.reference());
        return run(c.txId(), "issue", code, request, () -> {
            TokenDef token = requireToken(code);
            requireHolder(c.to());
            requireWithinMaxSupply(token, c.amount());
            return new Decision(EventType.ISSUED, request);
        });
    }

    /** Earn rule: every full {@code kesPerToken} spent earns one token ("spend KES 1,000, earn 1 token"). */
    @Transactional
    public Result earn(String code, Commands.Earn c) {
        SortedMap<String, String> request = fields("account", c.account(), "spendKes", c.spendKes(),
                "reference", c.reference());
        return run(c.txId(), "earn", code, request, () -> {
            TokenDef token = requireToken(code);
            requireHolder(c.account());
            if (token.kesPerToken() == null) {
                throw new BusinessRuleException("Token " + code + " has no earn rule");
            }
            long earned = c.spendKes() / token.kesPerToken();
            if (earned == 0) {
                throw new BusinessRuleException("Spend of KES " + c.spendKes() + " is below the KES "
                        + token.kesPerToken() + " needed to earn one token");
            }
            requireWithinMaxSupply(token, earned);
            return new Decision(EventType.EARNED, fields("to", c.account(), "amount", earned,
                    "spendKes", c.spendKes(), "kesPerToken", token.kesPerToken(), "reference", c.reference()));
        });
    }

    @Transactional
    public Result transfer(String code, Commands.Transfer c) {
        SortedMap<String, String> request = fields("from", c.from(), "to", c.to(), "amount", c.amount(),
                "reference", c.reference());
        return run(c.txId(), "transfer", code, request, () -> {
            requireToken(code);
            requireHolder(c.from());
            requireHolder(c.to());
            if (c.from().equals(c.to())) {
                throw new BusinessRuleException("Cannot transfer to the same account");
            }
            requireFunds(c.from(), code, c.amount());
            return new Decision(EventType.TRANSFERRED, request);
        });
    }

    /** Moves the seller's tokens into escrow for the buyer, for the token's reservation window. */
    @Transactional
    public Result reserve(String code, Commands.Reserve c) {
        SortedMap<String, String> request = fields("seller", c.seller(), "buyer", c.buyer(), "amount", c.amount());
        return run(c.txId(), "reserve", code, request, () -> {
            TokenDef token = requireToken(code);
            requireHolder(c.seller());
            requireHolder(c.buyer());
            if (c.seller().equals(c.buyer())) {
                throw new BusinessRuleException("Seller and buyer must differ");
            }
            requireFunds(c.seller(), code, c.amount());
            Instant expiresAt = now().plus(token.reservationMinutes(), ChronoUnit.MINUTES);
            SortedMap<String, String> payload = new TreeMap<>(request);
            payload.put("reservationId", UUID.randomUUID().toString());
            payload.put("expiresAt", expiresAt.toString());
            return new Decision(EventType.RESERVED, payload);
        });
    }

    @Transactional
    public Result purchase(String code, UUID reservationId, Commands.Purchase c) {
        SortedMap<String, String> request = fields("reservationId", reservationId, "buyer", c.buyer(),
                "paymentRef", c.paymentRef());
        return run(c.txId(), "purchase", code, request, () -> {
            Registry.Reservation r = requireOpenReservation(code, reservationId);
            if (!r.buyer().equals(c.buyer())) {
                throw new BusinessRuleException("Reservation " + reservationId + " is held for another buyer");
            }
            if (!now().isBefore(r.expiresAt())) {
                throw new BusinessRuleException("Reservation " + reservationId + " expired at " + r.expiresAt());
            }
            return new Decision(EventType.PURCHASED, closing(r, "paymentRef", c.paymentRef()));
        });
    }

    @Transactional
    public Result release(String code, UUID reservationId, Commands.Release c) {
        return run(c.txId(), "release", code, fields("reservationId", reservationId), () -> {
            Registry.Reservation r = requireOpenReservation(code, reservationId);
            return new Decision(EventType.RESERVATION_RELEASED, closing(r, null, null));
        });
    }

    /** Called by the expiry job. The txId is derived from the reservation, so expiring twice is harmless. */
    @Transactional
    public Result expire(UUID reservationId) {
        Registry.Reservation found = registry.reservation(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation " + reservationId + " not found"));
        return run("expire:" + reservationId, "expire", found.tokenCode(), fields("reservationId", reservationId),
                () -> {
                    Registry.Reservation r = requireOpenReservation(found.tokenCode(), reservationId);
                    if (now().isBefore(r.expiresAt())) {
                        throw new BusinessRuleException("Reservation " + reservationId + " has not expired yet");
                    }
                    return new Decision(EventType.RESERVATION_EXPIRED, closing(r, null, null));
                });
    }

    @Transactional
    public Result redeem(String code, Commands.Redeem c) {
        SortedMap<String, String> request = fields("account", c.account(), "amount", c.amount(),
                "reference", c.reference());
        return run(c.txId(), "redeem", code, request, () -> {
            requireToken(code);
            requireHolder(c.account());
            requireFunds(c.account(), code, c.amount());
            return new Decision(EventType.REDEEMED, fields("from", c.account(), "amount", c.amount(),
                    "reference", c.reference()));
        });
    }

    // ---------------------------------------------------------------------------------------------

    private Result run(String txId, String command, String tokenCode, SortedMap<String, String> request,
            Supplier<Decision> decide) {
        actions.lockForWrite();
        String requestHash = Hashes.sha256(command + "\n" + tokenCode + "\n" + Hashes.canonical(request));
        Optional<ActionEvent> earlier = actions.findByTxId(txId);
        if (earlier.isPresent()) {
            if (!earlier.get().requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(txId);
            }
            return new Result(earlier.get(), true);
        }
        Decision decision = decide.get();
        ActionEvent event = actions.append(txId, decision.type(), tokenCode, decision.payload(), requestHash, now());
        projector.apply(event);
        return new Result(event, false);
    }

    private Instant now() {
        return clock.instant();
    }

    private TokenDef requireToken(String code) {
        return registry.token(code).orElseThrow(() -> new NotFoundException("Token " + code + " not found"));
    }

    private static void requireHolder(String account) {
        if (!Accounts.isHolder(account)) {
            throw new BusinessRuleException("'" + account + "' is not a valid account id (1-64 of A-Z, a-z, 0-9,"
                    + " '.', '_' or '-', starting with a letter or digit)");
        }
    }

    private void requireFunds(String account, String code, long amount) {
        long available = tokenLedger.balance(account, code);
        if (available < amount) {
            throw new BusinessRuleException(account + " has " + available + " " + code + ", needs " + amount);
        }
    }

    private void requireWithinMaxSupply(TokenDef token, long amount) {
        if (token.maxSupply() == null) {
            return;
        }
        long issued = tokenLedger.supply(token.code()).issued();
        if (issued + amount > token.maxSupply()) {
            throw new BusinessRuleException("Issuing " + amount + " would exceed the max supply of "
                    + token.maxSupply() + " (" + issued + " already issued)");
        }
    }

    private Registry.Reservation requireOpenReservation(String code, UUID id) {
        Registry.Reservation r = registry.reservation(id)
                .filter(found -> found.tokenCode().equals(code))
                .orElseThrow(() -> new NotFoundException("Reservation " + id + " not found for token " + code));
        if (r.status() != ReservationStatus.HELD) {
            throw new BusinessRuleException("Reservation " + id + " is already " + r.status());
        }
        return r;
    }

    private static SortedMap<String, String> closing(Registry.Reservation r, String extraKey, String extraValue) {
        return fields("reservationId", r.id(), "seller", r.seller(), "buyer", r.buyer(), "amount", r.amount(),
                extraKey, extraValue);
    }

    private static Registry.TokenKind parseKind(String kind) {
        try {
            return Registry.TokenKind.valueOf(kind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Token kind must be LOYALTY or ASSET");
        }
    }

    /** Builds a payload from key/value pairs, leaving out null keys and null values. */
    private static SortedMap<String, String> fields(Object... pairs) {
        SortedMap<String, String> out = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i] != null && pairs[i + 1] != null) {
                out.put((String) pairs[i], pairs[i + 1].toString());
            }
        }
        return out;
    }
}
