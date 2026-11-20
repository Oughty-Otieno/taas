package com.taas.outbox;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.taas.ledger.ActionEvent;
import com.taas.ledger.ActionLedger;

/**
 * The transactional outbox, polled after commit. An event reaches subscribers only once its transaction has
 * committed, so "did the customer receive their token?" is answered by the ledger, never by the broker.
 * Delivery is at-least-once; subscribers are idempotent (the PSP charge is keyed, webhooks carry the action id).
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final ActionLedger actions;
    private final List<OutboxSubscriber> subscribers;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OutboxPoller(JdbcTemplate jdbc, ActionLedger actions, List<OutboxSubscriber> subscribers,
            PlatformTransactionManager txManager, Clock clock) {
        this.jdbc = jdbc;
        this.actions = actions;
        this.subscribers = subscribers;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${taas.outbox.poll-interval-ms}")
    void tick() {
        pollOnce();
    }

    /** Publishes one batch of committed events; returns how many. */
    public int pollOnce() {
        Integer published = tx.execute(status -> {
            List<Object[]> rows = jdbc.query("""
                    SELECT id, action_id FROM outbox WHERE published_at IS NULL
                    ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
                    (rs, i) -> new Object[] {rs.getLong(1), rs.getObject(2, UUID.class)}, BATCH);
            for (Object[] row : rows) {
                actions.find((UUID) row[1]).ifPresent(this::deliver);
                jdbc.update("UPDATE outbox SET published_at = ? WHERE id = ?",
                        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), row[0]);
            }
            return rows.size();
        });
        return published == null ? 0 : published;
    }

    private void deliver(ActionEvent event) {
        for (OutboxSubscriber s : subscribers) {
            try {
                s.onEvent(event);
            } catch (Exception e) {
                log.warn("{} failed on {} #{}: {}", s.getClass().getSimpleName(), event.type(), event.seq(),
                        e.toString());
            }
        }
    }
}
