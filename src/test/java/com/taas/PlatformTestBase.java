package com.taas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.taas.api.DevController;
import com.taas.api.JwtIssuer;
import com.taas.ledger.Projector;
import com.taas.ledger.TokenLedger;
import com.taas.outbox.OutboxPoller;
import com.taas.payment.PaymentSignature;
import com.taas.workflow.Timers;
import com.taas.workflow.WorkflowEngine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlatformTestBase.Clocks.class)
abstract class PlatformTestBase {

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static final String LOYALTY_V7 = """
            {"ruleSetId":"loyalty.default","version":"v7","effectiveFrom":"2020-01-01T00:00:00Z",
             "rules":[
              {"id":"tier-base","when":"event.type == 'TXN_SETTLED' && event.amountKes >= 1000",
               "then":{"award":{"tokenId":"LOYALTY_MPR","units":1}},"stopOnMatch":true},
              {"id":"tier-high","when":"event.type == 'TXN_SETTLED' && event.amountKes >= 50000",
               "then":{"award":{"tokenId":"LOYALTY_MPR","units":100}},"priority":10,"stopOnMatch":true}]}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired TokenLedger tokenLedger;
    @Autowired Projector projector;
    @Autowired WorkflowEngine engine;
    @Autowired Timers timers;
    @Autowired OutboxPoller outbox;
    @Autowired PaymentSignature pspSignature;
    @Autowired JwtIssuer issuer;

    private int keys;

    @BeforeEach
    void cleanDatabase() {
        // History refuses TRUNCATE; tests lift that guard only to start from an empty ledger.
        jdbc.execute("ALTER TABLE action_event DISABLE TRIGGER action_event_no_truncate");
        jdbc.execute("TRUNCATE outbox, idempotency_record, token_movement, holding, reservation, purchase_order,"
                + " rule_set_version, token, business, action_event RESTART IDENTITY");
        jdbc.execute("ALTER TABLE action_event ENABLE TRIGGER action_event_no_truncate");
        // Customers back to the migration's seed: the imported ones verified, peter without KYC.
        jdbc.update("DELETE FROM customer_pii");
        jdbc.update("DELETE FROM account_profile WHERE registered_at IS NOT NULL");
        jdbc.update("""
                UPDATE account_profile SET kyc_status = CASE WHEN account_id = 'peter' THEN 'NONE' ELSE 'VERIFIED' END,
                       kyc_tier = CASE WHEN account_id = 'peter' THEN 0 ELSE 2 END, kyc_case_id = NULL,
                       kyc_tier_requested = NULL, kyc_review = NULL, kyc_note = NULL""");
        // Staff back to the migration's five users, one per role, all active.
        jdbc.update("DELETE FROM staff_user WHERE created_by <> 'migration'");
        jdbc.update("""
                UPDATE staff_user SET status = 'ACTIVE', status_note = NULL, role = CASE user_id
                    WHEN 'amina.yusuf' THEN 'admin' WHEN 'brian.odhiambo' THEN 'rules-manager'
                    WHEN 'faith.chebet' THEN 'operations' WHEN 'david.mwangi' THEN 'compliance' ELSE 'auditor' END""");
        clock.reset();
    }

    @AfterEach
    void restoreGuards() {
        jdbc.execute("ALTER TABLE action_event ENABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE token_movement ENABLE TRIGGER USER");
    }

    // --- Fixtures ----------------------------------------------------------------------------------

    void loyalty() throws Exception {
        write("/v1/tokens", """
                {"tokenId":"LOYALTY_MPR","name":"M-Rewards","kind":"LOYALTY","authorisedSupply":1000000,
                 "redeemValueKes":10,"inactivityMonths":12}""").andExpect(status().isCreated());
        write("/v1/rules/versions", "{\"document\":" + LOYALTY_V7 + "}").andExpect(status().isCreated());
        write("/v1/issuances", "{\"tokenId\":\"LOYALTY_MPR\",\"units\":100000}").andExpect(status().isCreated());
    }

    /** An asset token with 10,000 units issued; the 20% cap is 2,000 units. */
    void asset(String tokenId) throws Exception {
        write("/v1/tokens", "{\"tokenId\":\"" + tokenId + "\",\"name\":\"Apartment\",\"kind\":\"ASSET\","
                + "\"assetType\":\"Apartment\",\"authorisedSupply\":10000,\"unitPriceKes\":25,\"maxHolderPercent\":20,"
                + "\"reservationMinutes\":15}").andExpect(status().isCreated());
        write("/v1/issuances", "{\"tokenId\":\"" + tokenId + "\",\"units\":10000}").andExpect(status().isCreated());
    }

    JsonNode award(String account, long amountKes) throws Exception {
        return body(write("/v1/awards", "{\"accountId\":\"" + account + "\",\"transaction\":{\"type\":\"TXN_SETTLED\","
                + "\"amountKes\":" + amountKes + "}}"));
    }

    String reserve(String tokenId, String account, long units) throws Exception {
        return body(write("/v1/reservations", "{\"tokenId\":\"" + tokenId + "\",\"accountId\":\"" + account
                + "\",\"units\":" + units + "}").andExpect(status().isCreated())).get("reservationId").asString();
    }

    String order(String reservationId) throws Exception {
        return body(write("/v1/orders", "{\"reservationId\":\"" + reservationId + "\"}")
                .andExpect(status().isAccepted())).get("orderId").asString();
    }

    ResultActions callback(String orderId, String status, long amountKes) throws Exception {
        String body = json.writeValueAsString(Map.of("pspEventId", "evt-" + UUID.randomUUID(), "orderId", orderId,
                "status", status, "amountKes", amountKes, "pspReference", "PSP-TEST"));
        return signedCallback(body);
    }

    ResultActions signedCallback(String body) throws Exception {
        return signedPost("/v1/payments/callback", body);
    }

    /** A webhook from the PSP, signed as the real one would sign it. */
    ResultActions signedPost(String path, String body) throws Exception {
        long ts = clock.instant().getEpochSecond();
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(PaymentSignature.TIMESTAMP_HEADER, Long.toString(ts))
                .header(PaymentSignature.SIGNATURE_HEADER, pspSignature.sign(ts, body)));
    }

    /** Reserve, order, publish the order (the PSP is charged), and deliver a successful callback. */
    void buy(String tokenId, String account, long units) throws Exception {
        String orderId = order(reserve(tokenId, account, units));
        outbox.pollOnce();
        callback(orderId, "SUCCEEDED", units * 25).andExpect(status().isOk());
    }

    long available(String account, String tokenId) {
        return tokenLedger.holding(tokenId, account).available();
    }

    long reserved(String account, String tokenId) {
        return tokenLedger.holding(tokenId, account).reserved();
    }

    long count(String eventType) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM action_event WHERE event_type = ?", Long.class, eventType);
        return n == null ? 0 : n;
    }

    List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM action_event ORDER BY seq", String.class);
    }

    // --- HTTP --------------------------------------------------------------------------------------

    String token(String... scopes) {
        return issuer.issue("test-client", scopes.length == 0 ? DevController.ALL_SCOPES : List.of(scopes),
                java.time.Duration.ofHours(1));
    }

    ResultActions write(String path, String body) throws Exception {
        return write(path, "k-" + (++keys), body);
    }

    ResultActions write(String path, String key, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode read(String path) throws Exception {
        return body(mvc.perform(get(path).header("Authorization", "Bearer " + token())).andExpect(status().isOk()));
    }

    ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token()));
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
