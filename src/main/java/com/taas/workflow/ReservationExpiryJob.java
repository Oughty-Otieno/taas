package com.taas.workflow;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.taas.common.BusinessRuleException;
import com.taas.ledger.Registry;

/**
 * Releases reservations whose hold has run out. Expiry is recorded as an event like any other, so replay
 * reproduces it exactly instead of depending on when the replay happens to run.
 */
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final Registry registry;
    private final WorkflowEngine engine;
    private final Clock clock;

    public ReservationExpiryJob(Registry registry, WorkflowEngine engine, Clock clock) {
        this.registry = registry;
        this.engine = engine;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${taas.reservation-expiry-interval-ms}")
    void scheduledRun() {
        expireOverdue();
    }

    /** Expires every overdue reservation and returns how many it expired. */
    public int expireOverdue() {
        int expired = 0;
        for (UUID id : registry.overdueReservations(clock.instant())) {
            try {
                engine.expire(id);
                expired++;
            } catch (BusinessRuleException e) {
                // Purchased or released between the lookup and the lock: nothing to do.
                log.debug("Skipped expiring {}: {}", id, e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Expired {} reservation(s)", expired);
        }
        return expired;
    }
}
