package com.taas.workflow;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.taas.ledger.Registry;
import com.taas.ledger.TokenLedger;

/**
 * Durable timers. Their state is the projections (a HELD reservation past its expiry, a loyalty holding with no
 * recent movement), so a restart loses nothing: the next tick finds the same work. Each firing is keyed, so
 * firing twice records once.
 */
@Component
public class Timers {

    private static final Logger log = LoggerFactory.getLogger(Timers.class);

    private final Registry registry;
    private final TokenLedger tokenLedger;
    private final WorkflowEngine engine;
    private final Clock clock;

    public Timers(Registry registry, TokenLedger tokenLedger, WorkflowEngine engine, Clock clock) {
        this.registry = registry;
        this.tokenLedger = tokenLedger;
        this.engine = engine;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${taas.timers.reservation-expiry-interval-ms}")
    void reservationTick() {
        expireReservations();
    }

    @Scheduled(fixedDelayString = "${taas.timers.loyalty-inactivity-interval-ms}")
    void inactivityTick() {
        revokeInactiveLoyalty();
    }

    /** Expires every overdue reservation; returns how many. */
    public int expireReservations() {
        int expired = 0;
        for (UUID id : registry.overdueReservations(clock.instant())) {
            if (engine.expire(id).status() == 200) {
                expired++;
            }
        }
        if (expired > 0) {
            log.info("Expired {} reservation(s)", expired);
        }
        return expired;
    }

    /** Revokes loyalty units from holders inactive for longer than their token allows; returns how many. */
    public int revokeInactiveLoyalty() {
        int revoked = 0;
        for (Registry.TokenDef token : registry.tokens()) {
            if (token.kind() != Registry.TokenKind.LOYALTY || token.inactivityMonths() == null) {
                continue;
            }
            var cutoff = clock.instant().atZone(ZoneOffset.UTC).minusMonths(token.inactivityMonths()).toInstant();
            for (TokenLedger.Inactive holder : tokenLedger.inactiveHolders(token.tokenId(), cutoff)) {
                if (engine.revokeInactive(token.tokenId(), holder.accountId(), holder.lastActivity()).status() == 200) {
                    revoked++;
                }
            }
        }
        return revoked;
    }
}
