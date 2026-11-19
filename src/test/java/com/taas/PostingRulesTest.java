package com.taas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.taas.ledger.Accounts;
import com.taas.ledger.ActionEvent;
import com.taas.ledger.EventType;
import com.taas.ledger.PostingRules;
import com.taas.ledger.PostingRules.Leg;

class PostingRulesTest {

    private static final UUID RES = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void everyEventTypeIsDoubleEntry() {
        Map<String, String> all = Map.of("to", "alice", "from", "john", "seller", "john", "buyer", "alice",
                "amount", "5", "reservationId", RES.toString());
        for (EventType type : EventType.values()) {
            long sum = PostingRules.legsFor(event(type, all)).stream().mapToLong(Leg::amount).sum();
            assertThat(sum).as(type.name()).isZero();
        }
    }

    @Test
    void movementsGoThroughMintBurnAndEscrow() {
        Map<String, String> f = Map.of("to", "john", "from", "john", "seller", "john", "buyer", "alice",
                "amount", "5", "reservationId", RES.toString());
        assertThat(PostingRules.legsFor(event(EventType.ISSUED, f)))
                .containsExactly(new Leg(Accounts.MINT, -5), new Leg("john", 5));
        assertThat(PostingRules.legsFor(event(EventType.REDEEMED, f)))
                .containsExactly(new Leg("john", -5), new Leg(Accounts.BURN, 5));
        assertThat(PostingRules.legsFor(event(EventType.RESERVED, f)))
                .containsExactly(new Leg("john", -5), new Leg(Accounts.hold(RES), 5));
        assertThat(PostingRules.legsFor(event(EventType.PURCHASED, f)))
                .containsExactly(new Leg(Accounts.hold(RES), -5), new Leg("alice", 5));
        assertThat(PostingRules.legsFor(event(EventType.RESERVATION_EXPIRED, f)))
                .containsExactly(new Leg(Accounts.hold(RES), -5), new Leg("john", 5));
        assertThat(PostingRules.legsFor(event(EventType.TOKEN_CREATED, f))).isEmpty();
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> PostingRules.legsFor(event(EventType.ISSUED, Map.of("to", "j", "amount", "0"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ActionEvent event(EventType type, Map<String, String> fields) {
        return new ActionEvent(1, UUID.randomUUID(), "tx", type, "PTS", new TreeMap<>(fields), "r",
                Instant.EPOCH, "p", "h");
    }
}
