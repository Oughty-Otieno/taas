package com.taas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** The brief's judgment criteria: balances come from history, history is immutable, replay reproduces state. */
class LedgerTest extends PlatformTestBase {

    @Test
    void johnTransfersTwentyToAlice() throws Exception {
        loyalty();
        assertThat(award("john", 60_000).at("/awarded/LOYALTY_MPR").asLong()).isEqualTo(100);

        JsonNode transfer = body(write("/v1/transfers", """
                {"tokenId":"LOYALTY_MPR","fromAccountId":"john","toAccountId":"alice","units":20}""")
                .andExpect(status().isCreated()));

        assertThat(available("john", "LOYALTY_MPR")).isEqualTo(80);
        assertThat(available("alice", "LOYALTY_MPR")).isEqualTo(20);
        // The movement: two signed legs citing one action, summing to zero.
        JsonNode legs = read("/v1/ledger/movements?actionId=" + transfer.get("actionId").asString());
        assertThat(legs.size()).isEqualTo(2);
        assertThat(legs.get(0).get("units").asLong() + legs.get(1).get("units").asLong()).isZero();
        // ...and the event behind it, with its idempotency key and rule version.
        JsonNode event = read("/v1/ledger/actions/" + transfer.get("actionId").asString());
        assertThat(event.get("eventType").asString()).isEqualTo("TOKEN_TRANSFERRED");
        assertThat(event.get("idempotencyKey").asString()).isNotBlank();
        assertThat(event.get("ruleVersion").asString()).isEqualTo("policy:LOYALTY_MPR@v1");
    }

    @Test
    void balanceIsTheSumOfMovements() throws Exception {
        loyalty();
        award("john", 60_000);
        write("/v1/transfers", "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"john\",\"toAccountId\":\"alice\","
                + "\"units\":20}");
        write("/v1/redemptions", "{\"tokenId\":\"LOYALTY_MPR\",\"accountId\":\"john\",\"units\":10}");

        Long sum = jdbc.queryForObject("SELECT SUM(units) FROM token_movement WHERE account_id = 'john'", Long.class);
        assertThat(sum).isEqualTo(70);
        assertThat(read("/v1/accounts/john/portfolio").at("/holdings/0/available").asLong()).isEqualTo(70);
    }

    /** Issue 100, transfer 20, redeem 10: balance 70. Delete every balance table, replay, same answer. */
    @Test
    void deletingEveryBalanceTableAndReplayingGivesTheSameBalances() throws Exception {
        loyalty();
        award("john", 60_000);
        write("/v1/transfers", "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"john\",\"toAccountId\":\"alice\","
                + "\"units\":20}");
        write("/v1/redemptions", "{\"tokenId\":\"LOYALTY_MPR\",\"accountId\":\"john\",\"units\":10}");
        asset("RWA_APT001");
        buy("RWA_APT001", "alice", 250);
        reserve("RWA_APT001", "bob", 400);
        String lapsed = reserve("RWA_APT001", "mary", 30);
        clock.advance(Duration.ofMinutes(16));
        timers.expireReservations();
        Map<String, Long> before = tokenLedger.snapshot();
        assertThat(available("john", "LOYALTY_MPR")).isEqualTo(70);

        JsonNode wipe = body(perform(post("/v1/ledger/wipe")).andExpect(status().isOk()));
        assertThat(wipe.get("eventsInActionLedger").asLong()).isGreaterThan(10);
        assertThat(tokenLedger.snapshot()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM token_movement", Long.class)).isZero();

        perform(post("/v1/ledger/replay")).andExpect(status().isOk())
                .andExpect(jsonPath("$.holdingsIdentical").value(true))
                .andExpect(jsonPath("$.workflowIdentical").value(true))
                .andExpect(jsonPath("$.hashChainValid").value(true));
        assertThat(tokenLedger.snapshot()).isEqualTo(before);
        assertThat(available("john", "LOYALTY_MPR")).isEqualTo(70);
        assertThat(read("/v1/ledger/verify").get("consistent").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE reservation_id = ?::uuid", String.class,
                lapsed)).isEqualTo("EXPIRED");
    }

    @Test
    void historyCannotBeEditedOrDeleted() throws Exception {
        loyalty();
        award("john", 60_000);
        assertThatThrownBy(() -> jdbc.update("UPDATE action_event SET payload = '{}'::jsonb"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM action_event")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE token_movement SET units = units * 2"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM token_movement")).hasMessageContaining("append-only");
    }

    @Test
    void tamperingWithHistoryIsDetectedAndBlocksReplay() throws Exception {
        loyalty();
        award("john", 60_000);
        write("/v1/transfers", "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"john\",\"toAccountId\":\"alice\","
                + "\"units\":20}");
        jdbc.execute("ALTER TABLE action_event DISABLE TRIGGER action_event_no_update_delete");
        jdbc.update("UPDATE action_event SET payload = jsonb_set(payload, '{units}', '\"2\"')"
                + " WHERE event_type = 'TOKEN_TRANSFERRED'");
        jdbc.execute("ALTER TABLE action_event ENABLE TRIGGER action_event_no_update_delete");

        JsonNode verify = read("/v1/ledger/verify");
        assertThat(verify.get("hashChainValid").asBoolean()).isFalse();
        assertThat(verify.get("firstBrokenSeq").asLong()).isPositive();
        perform(post("/v1/ledger/replay")).andExpect(status().isConflict());
        assertThat(available("alice", "LOYALTY_MPR")).isEqualTo(20);   // projections untouched
    }

    @Test
    void proofLinksAnEventToTheHeadOfTheChain() throws Exception {
        loyalty();
        String first = award("john", 60_000).get("actionId").asString();
        award("mary", 1_200);
        JsonNode proof = read("/v1/ledger/actions/" + first + "/proof");
        assertThat(proof.get("eventHashValid").asBoolean()).isTrue();
        assertThat(proof.get("chainValid").asBoolean()).isTrue();
        assertThat(proof.get("chainToHead").size()).isEqualTo(2);
        assertThat(proof.get("headHash").asString()).isEqualTo(read("/v1/ledger/verify").get("headHash").asString());
    }

    @Test
    void outboxCarriesOnlyCommittedEvents() throws Exception {
        asset("RWA_APT001");
        long before = jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class);
        write("/v1/reservations", "{\"tokenId\":\"RWA_APT001\",\"accountId\":\"alice\",\"units\":2500}")
                .andExpect(status().isUnprocessableContent());
        // The rolled-back reservation left nothing; only the recorded rejection is waiting to publish.
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox WHERE id > ?", String.class, before))
                .containsExactly("REQUEST_REJECTED");
        assertThat(outbox.pollOnce()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class)).isZero();
    }

    @Test
    void ledgerExplorerFiltersAndPages() throws Exception {
        loyalty();
        award("john", 60_000);
        award("mary", 1_200);
        award("grace", 800);
        JsonNode page = read("/v1/ledger/actions?type=TOKEN_EARNED,AWARD_NOT_QUALIFIED&limit=2");
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.at("/items/0/eventType").asString()).isEqualTo("AWARD_NOT_QUALIFIED");
        assertThat(page.at("/items/0/outcome").asString()).isEqualTo("DECLINED");
        JsonNode next = read("/v1/ledger/actions?type=TOKEN_EARNED,AWARD_NOT_QUALIFIED&limit=2&cursor="
                + page.get("nextCursor").asLong());
        assertThat(next.at("/items/0/payload/accountId").asString()).isEqualTo("john");
        assertThat(read("/v1/ledger/actions?account=mary").get("items").size()).isEqualTo(1);
        perform(get("/v1/ledger/actions?assetType=LOYALTY")).andExpect(status().isOk());
    }
}
