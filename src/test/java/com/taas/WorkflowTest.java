package com.taas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.taas.workflow.CommandRunner.Caller;
import com.taas.workflow.Commands;
import com.taas.workflow.Reply;

import tools.jackson.databind.JsonNode;

class WorkflowTest extends PlatformTestBase {

    // --- Idempotency -------------------------------------------------------------------------------

    @Test
    void theSameKeyFiveTimesInParallelRecordsOnce() throws Exception {
        loyalty();
        Commands.Award request = new Commands.Award("john", null,
                new Commands.Transaction("TXN_SETTLED", 1_200, "m1", "POS", "TXN-1"));
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Callable<Reply>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(() -> engine.award(new Caller("pos-terminal", "txn-1"), request));
        }
        List<Reply> replies = new ArrayList<>();
        for (Future<Reply> f : pool.invokeAll(calls)) {
            replies.add(f.get());
        }
        pool.shutdown();

        assertThat(count("TOKEN_EARNED")).isEqualTo(1);
        assertThat(available("john", "LOYALTY_MPR")).isEqualTo(1);
        Set<String> actionIds = new HashSet<>();
        for (Reply r : replies) {
            assertThat(r.status()).isEqualTo(201);
            actionIds.add(json.valueToTree(r.body()).get("actionId").asString());
        }
        assertThat(actionIds).hasSize(1);
        assertThat(replies.stream().filter(Reply::replayed).count()).isEqualTo(4);
    }

    @Test
    void aRepeatIsAnsweredFromTheStoreWithTheSameStatusAndBody() throws Exception {
        loyalty();
        String body = "{\"accountId\":\"john\",\"transaction\":{\"type\":\"TXN_SETTLED\",\"amountKes\":1200}}";
        String first = write("/v1/awards", "same", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        write("/v1/awards", "same", body).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(r -> assertThat(json.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(json.readTree(first)));
        assertThat(count("TOKEN_EARNED")).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsRefused() throws Exception {
        loyalty();
        write("/v1/awards", "k", "{\"accountId\":\"john\",\"transaction\":{\"type\":\"TXN_SETTLED\",\"amountKes\":1200}}");
        write("/v1/awards", "k", "{\"accountId\":\"john\",\"transaction\":{\"type\":\"TXN_SETTLED\",\"amountKes\":9900}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(available("john", "LOYALTY_MPR")).isEqualTo(1);
    }

    @Test
    void aRejectionIsRecordedAndARetryGetsTheSameRefusal() throws Exception {
        loyalty();
        String transfer = "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"john\",\"toAccountId\":\"alice\",\"units\":50}";
        write("/v1/transfers", "t1", transfer).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_UNITS"));
        assertThat(count("REQUEST_REJECTED")).isEqualTo(1);

        award("john", 60_000);   // john can now afford it, but the key was already answered
        write("/v1/transfers", "t1", transfer).andExpect(status().isUnprocessableContent())
                .andExpect(header().string("Idempotent-Replay", "true"));
        assertThat(available("alice", "LOYALTY_MPR")).isZero();
        assertThat(count("REQUEST_REJECTED")).isEqualTo(1);
    }

    @Test
    void everyWriteNeedsAnIdempotencyKey() throws Exception {
        loyalty();
        perform(post("/v1/awards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"john\",\"transaction\":{\"type\":\"TXN_SETTLED\",\"amountKes\":1200}}"))
                .andExpect(status().isBadRequest());
    }

    // --- Concurrency: the cap and the supply belong to the database ------------------------------

    @Test
    void twoHundredParallelBidsNeverBreakTheCapOrTheSupply() throws Exception {
        asset("RWA_APT001");   // 10,000 units, cap 2,000 per holder
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Callable<Reply>> bids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String buyer = String.format("bidder-%02d", i % 8 + 1);
            Caller caller = new Caller("bid-storm", "bid-" + i);
            bids.add(() -> engine.reserve(caller, new Commands.Reserve("RWA_APT001", buyer, 100, null)));
        }
        Map<Integer, Integer> statuses = new TreeMap<>();
        for (Future<Reply> f : pool.invokeAll(bids)) {
            statuses.merge(f.get().status(), 1, Integer::sum);
        }
        pool.shutdown();

        // 8 buyers could take 16,000 under the cap; the 10,000 supply binds first: exactly 100 bids fit.
        assertThat(statuses).containsEntry(201, 100).containsEntry(422, 100);
        assertThat(available("@treasury", "RWA_APT001")).isZero();
        for (int b = 1; b <= 8; b++) {
            assertThat(reserved(String.format("bidder-%02d", b), "RWA_APT001")).isLessThanOrEqualTo(2_000);
        }
        assertThat(count("REQUEST_REJECTED")).isEqualTo(100);
        assertThat(projector.verify().consistent()).isTrue();
    }

    @Test
    void reservationsCountTowardTheCap() throws Exception {
        asset("RWA_APT001");
        reserve("RWA_APT001", "alice", 1_500);
        write("/v1/reservations", "{\"tokenId\":\"RWA_APT001\",\"accountId\":\"alice\",\"units\":600}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_CAP"));
        reserve("RWA_APT001", "alice", 500);   // exactly 2,000: allowed
        assertThat(reserved("alice", "RWA_APT001")).isEqualTo(2_000);
    }

    @Test
    void theCapAppliesToTransfersToo() throws Exception {
        asset("RWA_APT001");
        buy("RWA_APT001", "alice", 2_000);
        buy("RWA_APT001", "bob", 100);
        write("/v1/transfers", "{\"tokenId\":\"RWA_APT001\",\"fromAccountId\":\"bob\",\"toAccountId\":\"alice\",\"units\":1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OWNERSHIP_CAP"));
        assertThat(available("alice", "RWA_APT001")).isEqualTo(2_000);
    }

    @Test
    void issuingBeyondTheAuthorisedSupplyIsRefusedByTheDatabase() throws Exception {
        asset("RWA_APT001");
        write("/v1/issuances", "{\"tokenId\":\"RWA_APT001\",\"units\":1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SUPPLY_CEILING"));
    }

    // --- Asset purchase: reserve, pay, assign, and the path where the timer wins -----------------

    @Test
    void reservePayAndAssign() throws Exception {
        asset("RWA_APT001");
        JsonNode reservation = body(write("/v1/reservations",
                "{\"tokenId\":\"RWA_APT001\",\"accountId\":\"alice\",\"units\":250}").andExpect(status().isCreated()));
        assertThat(reservation.get("expiresAt").asString()).isNotBlank();
        assertThat(reserved("alice", "RWA_APT001")).isEqualTo(250);

        String orderId = order(reservation.get("reservationId").asString());
        assertThat(read("/v1/orders/" + orderId).at("/order/status").asString()).isEqualTo("AWAITING_PAYMENT");
        outbox.pollOnce();   // ORDER_PLACED published: the PSP is charged after the commit

        String body = json.writeValueAsString(Map.of("pspEventId", "evt-1", "orderId", orderId, "status",
                "SUCCEEDED", "amountKes", 6_250, "pspReference", "PSP-1"));
        signedCallback(body).andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("PAID"));
        signedCallback(body).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(available("alice", "RWA_APT001")).isEqualTo(250);
        assertThat(reserved("alice", "RWA_APT001")).isZero();
        JsonNode state = read("/v1/orders/" + orderId);
        assertThat(state.at("/order/status").asString()).isEqualTo("PAID");
        assertThat(state.at("/portfolio/0/available").asLong()).isEqualTo(250);
        assertThat(eventTypes()).endsWith("TOKEN_RESERVED", "ORDER_PLACED", "PAYMENT_RECEIVED", "TOKEN_PURCHASED");
    }

    @Test
    void anUnsignedOrStaleCallbackIsRefused() throws Exception {
        asset("RWA_APT001");
        String orderId = order(reserve("RWA_APT001", "alice", 10));
        String body = json.writeValueAsString(Map.of("pspEventId", "e", "orderId", orderId, "status", "SUCCEEDED",
                "amountKes", 250));
        mvc.perform(post("/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-PSP-Timestamp", "1").header("X-PSP-Signature", "00"))
                .andExpect(status().isUnauthorized());
        long stale = clock.instant().minus(Duration.ofMinutes(10)).getEpochSecond();
        mvc.perform(post("/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-PSP-Timestamp", Long.toString(stale))
                .header("X-PSP-Signature", pspSignature.sign(stale, body)))
                .andExpect(status().isUnauthorized());
        assertThat(count("PAYMENT_RECEIVED")).isZero();
    }

    @Test
    void aFailedPaymentReturnsTheUnits() throws Exception {
        asset("RWA_APT001");
        String orderId = order(reserve("RWA_APT001", "bob", 1_000));
        callback(orderId, "FAILED", 25_000).andExpect(jsonPath("$.orderStatus").value("PAYMENT_FAILED"));
        assertThat(reserved("bob", "RWA_APT001")).isZero();
        assertThat(available("@treasury", "RWA_APT001")).isEqualTo(10_000);
        assertThat(count("PAYMENT_FAILED")).isEqualTo(1);
    }

    @Test
    void whenTheTimerWinsTheUnitsReturnAndALaterOrderFailsCleanly() throws Exception {
        asset("RWA_APT001");
        String reservationId = reserve("RWA_APT001", "alice", 250);
        String orderId = order(reservationId);
        clock.advance(Duration.ofMinutes(16));
        assertThat(timers.expireReservations()).isEqualTo(1);
        assertThat(timers.expireReservations()).isZero();   // firing again records nothing

        assertThat(count("TOKEN_RESERVATION_EXPIRED")).isEqualTo(1);
        assertThat(reserved("alice", "RWA_APT001")).isZero();
        assertThat(available("@treasury", "RWA_APT001")).isEqualTo(10_000);
        assertThat(read("/v1/orders/" + orderId).at("/order/status").asString()).isEqualTo("EXPIRED");
        // The reservation stays visible: expiry is a reversing movement, not a deleted row.
        assertThat(read("/v1/ledger/actions?related=" + reservationId).get("items").size()).isEqualTo(3);

        write("/v1/orders", "{\"reservationId\":\"" + reservationId + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));

        // The payment arrives anyway: money taken, nothing delivered, so a refund is recorded.
        callback(orderId, "SUCCEEDED", 6_250).andExpect(jsonPath("$.orderStatus").value("REFUND_REQUIRED"));
        assertThat(available("alice", "RWA_APT001")).isZero();
        assertThat(count("PAYMENT_REFUND_REQUIRED")).isEqualTo(1);
    }

    @Test
    void anOrderAfterTheHoldEndsIsRefusedEvenBeforeTheTimerRuns() throws Exception {
        asset("RWA_APT001");
        String reservationId = reserve("RWA_APT001", "alice", 250);
        clock.advance(Duration.ofMinutes(15));
        write("/v1/orders", "{\"reservationId\":\"" + reservationId + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));
    }

    @Test
    void releasingEarlyReturnsTheUnits() throws Exception {
        asset("RWA_APT001");
        String reservationId = reserve("RWA_APT001", "alice", 250);
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/v1/reservations/" + reservationId).header("Idempotency-Key", "rel-1"))
                .andExpect(status().isOk());
        assertThat(reserved("alice", "RWA_APT001")).isZero();
        assertThat(count("TOKEN_RESERVATION_RELEASED")).isEqualTo(1);
    }

    // --- Policy decision point ---------------------------------------------------------------------

    @Test
    void anUnverifiedCustomerIsDeniedAndTheDenyIsRecorded() throws Exception {
        asset("RWA_APT001");
        write("/v1/reservations", "{\"tokenId\":\"RWA_APT001\",\"accountId\":\"peter\",\"units\":10}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("KYC_REQUIRED"));
        assertThat(count("REQUEST_REJECTED")).isEqualTo(1);
        assertThat(count("TOKEN_RESERVED")).isZero();
    }

    @Test
    void velocityLimitsTransfersPerHour() throws Exception {
        loyalty();
        award("john", 60_000);
        for (int i = 0; i < 3; i++) {
            write("/v1/transfers", "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"john\",\"toAccountId\":\"alice\","
                    + "\"units\":1}").andExpect(status().isCreated());
        }
        write("/v1/transfers", "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"john\",\"toAccountId\":\"alice\","
                + "\"units\":1}").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("VELOCITY_LIMIT"));
        clock.advance(Duration.ofMinutes(61));
        write("/v1/transfers", "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"john\",\"toAccountId\":\"alice\","
                + "\"units\":1}").andExpect(status().isCreated());
    }

    @Test
    void loyaltyUnitsLapseAfterTwelveMonthsOfInactivity() throws Exception {
        loyalty();
        award("john", 60_000);
        award("mary", 1_200);
        clock.advance(Duration.ofDays(200));
        award("mary", 1_200);   // mary stays active
        clock.advance(Duration.ofDays(200));
        assertThat(timers.revokeInactiveLoyalty()).isEqualTo(1);
        assertThat(available("john", "LOYALTY_MPR")).isZero();
        assertThat(available("mary", "LOYALTY_MPR")).isEqualTo(2);
        assertThat(count("TOKEN_REVOKED")).isEqualTo(1);
    }

    // --- Access ------------------------------------------------------------------------------------

    @Test
    void scopesAreCheckedBeforeTheRequestReachesAService() throws Exception {
        mvc.perform(get("/v1/tokens")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/tokens").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/awards").header("Authorization", "Bearer " + token("token:read"))
                .header("Idempotency-Key", "x").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/ledger/replay").header("Authorization", "Bearer " + token("ledger:read")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/tokens").header("Authorization", "Bearer " + token("token:read")))
                .andExpect(status().isOk());
    }
}
