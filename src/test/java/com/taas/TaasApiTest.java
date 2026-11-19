package com.taas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.taas.common.BusinessRuleException;
import com.taas.ledger.Projector;
import com.taas.ledger.TokenLedger;
import com.taas.workflow.Commands;
import com.taas.workflow.ReservationExpiryJob;
import com.taas.workflow.WorkflowEngine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaasApiTest {

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired TokenLedger tokenLedger;
    @Autowired Projector projector;
    @Autowired WorkflowEngine engine;
    @Autowired ReservationExpiryJob expiryJob;

    @BeforeEach
    void cleanDatabase() {
        // The Action Ledger refuses TRUNCATE; tests lift that guard only to start from an empty history.
        jdbc.execute("ALTER TABLE action_event DISABLE TRIGGER action_event_no_truncate");
        jdbc.execute("TRUNCATE posting, balance, token, reservation, action_event RESTART IDENTITY");
        jdbc.execute("ALTER TABLE action_event ENABLE TRIGGER action_event_no_truncate");
        clock.reset();
    }

    @AfterEach
    void restoreGuards() {
        jdbc.execute("ALTER TABLE action_event ENABLE TRIGGER USER");
    }

    // --- The brief's scenarios --------------------------------------------------------------------

    @Test
    void johnTransfersTwentyToAlice() throws Exception {
        createLoyalty("PTS");
        send("/api/tokens/PTS/issue", "{\"txId\":\"t1\",\"to\":\"john\",\"amount\":100}").andExpect(status().isCreated());
        send("/api/tokens/PTS/transfer", "{\"txId\":\"t2\",\"from\":\"john\",\"to\":\"alice\",\"amount\":20}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFERRED"))
                .andExpect(jsonPath("$.txId").value("t2"));

        assertThat(balance("john", "PTS")).isEqualTo(80);
        assertThat(balance("alice", "PTS")).isEqualTo(20);
    }

    @Test
    void balanceIsTheSumOfTheStatement() throws Exception {
        createLoyalty("PTS");
        send("/api/tokens/PTS/issue", "{\"txId\":\"e1\",\"to\":\"john\",\"amount\":100}");
        send("/api/tokens/PTS/transfer", "{\"txId\":\"e2\",\"from\":\"john\",\"to\":\"alice\",\"amount\":20}");
        send("/api/tokens/PTS/redeem", "{\"txId\":\"e3\",\"account\":\"john\",\"amount\":10}");

        JsonNode statement = read(get("/api/accounts/john/statement?token=PTS"));
        assertThat(statement.get("closingBalance").asLong()).isEqualTo(70);
        assertThat(statement.get("lines")).extracting(l -> l.get("type").asString())
                .containsExactly("ISSUED", "TRANSFERRED", "REDEEMED");
        assertThat(statement.get("lines")).extracting(l -> l.get("runningBalance").asLong())
                .containsExactly(100L, 80L, 70L);
        assertThat(statement.get("lines").get(1).get("counterparty").asString()).isEqualTo("alice");

        JsonNode token = read(get("/api/tokens/PTS"));
        assertThat(token.at("/supply/issued").asLong()).isEqualTo(100);
        assertThat(token.at("/supply/redeemed").asLong()).isEqualTo(10);
        assertThat(token.at("/supply/circulating").asLong()).isEqualTo(90);
    }

    /** The replay test: delete every balance table, keep only the Action Ledger, rebuild, compare. */
    @Test
    void deletingAllBalanceTablesAndReplayingGivesTheSameBalances() throws Exception {
        buildMixedHistory();
        Map<String, Long> before = tokenLedger.snapshot();
        JsonNode reservationsBefore = read(get("/api/accounts/treasury/balances"));
        assertThat(before).containsEntry("john|PTS", 70L).containsEntry("alice|PTS", 20L);

        jdbc.update("DELETE FROM balance");
        jdbc.update("DELETE FROM posting");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM token");
        assertThat(tokenLedger.snapshot()).isEmpty();

        JsonNode report = read(post("/api/admin/replay"));
        assertThat(report.get("eventsReplayed").asLong()).isEqualTo(13);
        assertThat(report.get("hashChainValid").asBoolean()).isTrue();

        assertThat(tokenLedger.snapshot()).isEqualTo(before);
        assertThat(read(get("/api/accounts/treasury/balances"))).isEqualTo(reservationsBefore);
        assertThat(read(get("/api/admin/verify")).get("consistent").asBoolean()).isTrue();
    }

    @Test
    void replayOverIntactProjectionsReportsThemIdentical() throws Exception {
        buildMixedHistory();
        JsonNode report = read(post("/api/admin/replay"));
        assertThat(report.get("balancesIdentical").asBoolean()).isTrue();
        assertThat(report.get("reservationsIdentical").asBoolean()).isTrue();
        assertThat(report.get("differences").isEmpty()).isTrue();
    }

    // --- Idempotency ------------------------------------------------------------------------------

    @Test
    void repeatingATransactionIdMovesNothing() throws Exception {
        createLoyalty("PTS");
        send("/api/tokens/PTS/issue", "{\"txId\":\"i1\",\"to\":\"john\",\"amount\":100}");
        String transfer = "{\"txId\":\"dup\",\"from\":\"john\",\"to\":\"alice\",\"amount\":20}";
        long seq = read(send("/api/tokens/PTS/transfer", transfer)).get("seq").asLong();

        send("/api/tokens/PTS/transfer", transfer)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.seq").value(seq));

        assertThat(balance("john", "PTS")).isEqualTo(80);
        assertThat(balance("alice", "PTS")).isEqualTo(20);
    }

    @Test
    void reusingATransactionIdForADifferentRequestIsRefused() throws Exception {
        createLoyalty("PTS");
        send("/api/tokens/PTS/issue", "{\"txId\":\"i1\",\"to\":\"john\",\"amount\":100}");
        send("/api/tokens/PTS/transfer", "{\"txId\":\"x\",\"from\":\"john\",\"to\":\"alice\",\"amount\":20}");
        send("/api/tokens/PTS/transfer", "{\"txId\":\"x\",\"from\":\"john\",\"to\":\"alice\",\"amount\":25}")
                .andExpect(status().isConflict());
        assertThat(balance("alice", "PTS")).isEqualTo(20);
    }

    // --- Workflow rules ---------------------------------------------------------------------------

    @Test
    void earnRuleGivesOneTokenPerThousandShillings() throws Exception {
        createLoyalty("PTS");
        send("/api/tokens/PTS/earn", "{\"txId\":\"s1\",\"account\":\"john\",\"spendKes\":2500}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payload.amount").value("2"))
                .andExpect(jsonPath("$.payload.spendKes").value("2500"));
        send("/api/tokens/PTS/earn", "{\"txId\":\"s2\",\"account\":\"john\",\"spendKes\":999}")
                .andExpect(status().isUnprocessableContent());
        assertThat(balance("john", "PTS")).isEqualTo(2);
    }

    @Test
    void refusesOverdraftsUnknownTokensAndSystemAccounts() throws Exception {
        createLoyalty("PTS");
        send("/api/tokens/PTS/issue", "{\"txId\":\"i1\",\"to\":\"john\",\"amount\":10}");
        send("/api/tokens/PTS/transfer", "{\"txId\":\"o1\",\"from\":\"john\",\"to\":\"alice\",\"amount\":11}")
                .andExpect(status().isUnprocessableContent());
        send("/api/tokens/PTS/transfer", "{\"txId\":\"o2\",\"from\":\"@mint\",\"to\":\"alice\",\"amount\":5}")
                .andExpect(status().isUnprocessableContent());
        send("/api/tokens/NOPE/issue", "{\"txId\":\"o3\",\"to\":\"john\",\"amount\":1}")
                .andExpect(status().isNotFound());
        send("/api/tokens/PTS/issue", "{\"txId\":\"o4\",\"to\":\"john\",\"amount\":-1}")
                .andExpect(status().isBadRequest());
        // Refused requests leave no trace and do not burn their txId.
        assertThat(read(get("/api/events")).size()).isEqualTo(2);
        send("/api/tokens/PTS/transfer", "{\"txId\":\"o1\",\"from\":\"john\",\"to\":\"alice\",\"amount\":10}")
                .andExpect(status().isCreated());
    }

    @Test
    void requiresAnApiKey() throws Exception {
        mvc.perform(get("/api/tokens")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tokens").header("X-API-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tokens").header("X-API-Key", "test-key")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void assetSupplyIsCapped() throws Exception {
        createAsset("APT-101");
        send("/api/tokens/APT-101/issue", "{\"txId\":\"a1\",\"to\":\"treasury\",\"amount\":1000}")
                .andExpect(status().isCreated());
        send("/api/tokens/APT-101/issue", "{\"txId\":\"a2\",\"to\":\"treasury\",\"amount\":1}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void reservationHoldsTokensUntilPurchased() throws Exception {
        createAsset("APT-101");
        send("/api/tokens/APT-101/issue", "{\"txId\":\"a1\",\"to\":\"treasury\",\"amount\":1000}");
        String id = reserve("r1", 10);

        JsonNode treasury = read(get("/api/accounts/treasury/balances")).get(0);
        assertThat(treasury.get("available").asLong()).isEqualTo(990);
        assertThat(treasury.get("reservedForSale").asLong()).isEqualTo(10);

        send("/api/tokens/APT-101/reservations/" + id + "/purchase",
                "{\"txId\":\"p0\",\"buyer\":\"bob\",\"paymentRef\":\"MPESA-1\"}")
                .andExpect(status().isUnprocessableContent());
        send("/api/tokens/APT-101/reservations/" + id + "/purchase",
                "{\"txId\":\"p1\",\"buyer\":\"alice\",\"paymentRef\":\"MPESA-1\"}")
                .andExpect(status().isCreated());
        send("/api/tokens/APT-101/reservations/" + id + "/purchase",
                "{\"txId\":\"p2\",\"buyer\":\"alice\",\"paymentRef\":\"MPESA-2\"}")
                .andExpect(status().isUnprocessableContent());

        assertThat(balance("alice", "APT-101")).isEqualTo(10);
        assertThat(balance("treasury", "APT-101")).isEqualTo(990);
        assertThat(read(get("/api/tokens/APT-101/reservations/" + id)).get("status").asString())
                .isEqualTo("PURCHASED");
    }

    @Test
    void unpaidReservationExpiresAfterFifteenMinutes() throws Exception {
        createAsset("APT-101");
        send("/api/tokens/APT-101/issue", "{\"txId\":\"a1\",\"to\":\"treasury\",\"amount\":1000}");
        String id = reserve("r1", 10);

        clock.advance(Duration.ofMinutes(14));
        assertThat(expiryJob.expireOverdue()).isZero();

        clock.advance(Duration.ofMinutes(2));
        send("/api/tokens/APT-101/reservations/" + id + "/purchase",
                "{\"txId\":\"late\",\"buyer\":\"alice\",\"paymentRef\":\"MPESA-9\"}")
                .andExpect(status().isUnprocessableContent());
        assertThat(expiryJob.expireOverdue()).isEqualTo(1);
        assertThat(expiryJob.expireOverdue()).isZero();

        assertThat(balance("treasury", "APT-101")).isEqualTo(1000);
        assertThat(read(get("/api/events/expire:" + id)).get("type").asString()).isEqualTo("RESERVATION_EXPIRED");
    }

    @Test
    void releaseReturnsTokensToTheSeller() throws Exception {
        createAsset("APT-101");
        send("/api/tokens/APT-101/issue", "{\"txId\":\"a1\",\"to\":\"treasury\",\"amount\":1000}");
        String id = reserve("r1", 10);
        send("/api/tokens/APT-101/reservations/" + id + "/release", "{\"txId\":\"rel\"}")
                .andExpect(status().isCreated());
        assertThat(balance("treasury", "APT-101")).isEqualTo(1000);
    }

    // --- Integrity --------------------------------------------------------------------------------

    @Test
    void historyCannotBeEditedOrDeleted() throws Exception {
        createLoyalty("PTS");
        assertThatThrownBy(() -> jdbc.update("UPDATE action_event SET tx_id = 'x'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM action_event"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE action_event CASCADE"))
                .hasMessageContaining("append-only");
    }

    @Test
    void tamperingWithHistoryIsDetectedAndBlocksReplay() throws Exception {
        buildMixedHistory();
        Map<String, Long> before = tokenLedger.snapshot();
        // Someone with database access bypasses the guard and inflates an issuance.
        jdbc.execute("ALTER TABLE action_event DISABLE TRIGGER action_event_no_update_delete");
        jdbc.update("UPDATE action_event SET payload = jsonb_set(payload, '{amount}', '\"1000\"') WHERE seq = 2");
        jdbc.execute("ALTER TABLE action_event ENABLE TRIGGER action_event_no_update_delete");

        JsonNode verify = read(get("/api/admin/verify"));
        assertThat(verify.get("hashChainValid").asBoolean()).isFalse();
        assertThat(verify.get("firstBrokenSeq").asLong()).isEqualTo(2);

        send("/api/admin/replay", "{}").andExpect(status().isInternalServerError());
        assertThat(tokenLedger.snapshot()).isEqualTo(before);
    }

    @Test
    void concurrentTransfersNeverOverdraw() throws Exception {
        engine.createToken(new Commands.CreateToken("c0", "PTS", "Points", "LOYALTY", 1000L, null, null));
        engine.issue("PTS", new Commands.Issue("c1", "john", 100, null));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String txId = "c-" + i;
            tasks.add(() -> {
                try {
                    engine.transfer("PTS", new Commands.Transfer(txId, "john", "alice", 10, null));
                    return true;
                } catch (BusinessRuleException e) {
                    return false;
                }
            });
        }
        long succeeded = 0;
        for (Future<Boolean> f : pool.invokeAll(tasks)) {
            succeeded += f.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(10);
        assertThat(balance("john", "PTS")).isZero();
        assertThat(balance("alice", "PTS")).isEqualTo(100);
        assertThat(projector.verify().consistent()).isTrue();
    }

    // --- Helpers ----------------------------------------------------------------------------------

    /** 13 events across a loyalty token and an asset token, including an expired reservation. */
    private void buildMixedHistory() throws Exception {
        createLoyalty("PTS");                                                                        // 1
        send("/api/tokens/PTS/issue", "{\"txId\":\"m1\",\"to\":\"john\",\"amount\":100}");           // 2
        send("/api/tokens/PTS/transfer", "{\"txId\":\"m2\",\"from\":\"john\",\"to\":\"alice\",\"amount\":20}");
        send("/api/tokens/PTS/redeem", "{\"txId\":\"m3\",\"account\":\"john\",\"amount\":10}");      // 4
        send("/api/tokens/PTS/earn", "{\"txId\":\"m4\",\"account\":\"mary\",\"spendKes\":5400}");    // 5
        createAsset("APT-101");                                                                      // 6
        send("/api/tokens/APT-101/issue", "{\"txId\":\"m5\",\"to\":\"treasury\",\"amount\":1000}");  // 7
        String sold = reserve("m6", 50);                                                             // 8
        send("/api/tokens/APT-101/reservations/" + sold + "/purchase",
                "{\"txId\":\"m7\",\"buyer\":\"alice\",\"paymentRef\":\"MPESA-1\"}");                 // 9
        String lapsed = reserve("m8", 30);                                                           // 10
        clock.advance(Duration.ofMinutes(16));
        expiryJob.expireOverdue();                                                                   // 11
        reserve("m9", 5);                                                                            // 12 (open)
        send("/api/tokens/APT-101/transfer",
                "{\"txId\":\"m10\",\"from\":\"alice\",\"to\":\"bob\",\"amount\":15}");               // 13
        assertThat(read(get("/api/tokens/APT-101/reservations/" + lapsed)).get("status").asString())
                .isEqualTo("EXPIRED");
    }

    private void createLoyalty(String code) throws Exception {
        send("/api/tokens", "{\"txId\":\"create-" + code + "\",\"code\":\"" + code
                + "\",\"name\":\"Loyalty points\",\"kind\":\"LOYALTY\",\"kesPerToken\":1000}")
                .andExpect(status().isCreated());
    }

    private void createAsset(String code) throws Exception {
        send("/api/tokens", "{\"txId\":\"create-" + code + "\",\"code\":\"" + code
                + "\",\"name\":\"Apartment 101\",\"kind\":\"ASSET\",\"maxSupply\":1000,\"reservationMinutes\":15}")
                .andExpect(status().isCreated());
    }

    private String reserve(String txId, long amount) throws Exception {
        JsonNode event = read(send("/api/tokens/APT-101/reserve", "{\"txId\":\"" + txId
                + "\",\"seller\":\"treasury\",\"buyer\":\"alice\",\"amount\":" + amount + "}"));
        return event.at("/payload/reservationId").asString();
    }

    private long balance(String account, String token) {
        return tokenLedger.balance(account, token);
    }

    private ResultActions send(String path, String body) throws Exception {
        return mvc.perform(post(path).header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private JsonNode read(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return read(mvc.perform(request.header("X-API-Key", "test-key")));
    }
}
