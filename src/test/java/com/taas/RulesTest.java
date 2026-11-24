package com.taas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class RulesTest extends PlatformTestBase {

    @Test
    void theClientSendsATransactionAndTheRulesDecide() throws Exception {
        loyalty();
        // A units field in the request is not part of the contract and changes nothing.
        JsonNode reply = body(write("/v1/awards", """
                {"accountId":"john","units":5000,"transaction":{"type":"TXN_SETTLED","amountKes":1200}}""")
                .andExpect(status().isCreated()));
        assertThat(reply.at("/awarded/LOYALTY_MPR").asLong()).isEqualTo(1);
        assertThat(reply.get("ruleSet").asString()).isEqualTo("loyalty.default@v7");
        assertThat(reply.at("/balance/0/available").asLong()).isEqualTo(1);
        JsonNode event = read("/v1/ledger/actions/" + reply.get("actionId").asString());
        assertThat(event.get("ruleVersion").asString()).isEqualTo("loyalty.default@v7");
        assertThat(event.at("/payload/matched").asString()).isEqualTo("tier-base:1");
    }

    @Test
    void overlapIsResolvedByPriorityAndStopOnMatch() throws Exception {
        loyalty();
        assertThat(award("john", 60_000).at("/awarded/LOYALTY_MPR").asLong()).isEqualTo(100);

        // The same two rules without stopOnMatch on tier-high: both match, both award.
        write("/v1/rules/versions", "{\"document\":" + LOYALTY_V7.replace("\"v7\"", "\"v8\"")
                .replace("\"priority\":10,\"stopOnMatch\":true", "\"priority\":10") + "}")
                .andExpect(status().isCreated());
        JsonNode reply = award("mary", 60_000);
        assertThat(reply.at("/awarded/LOYALTY_MPR").asLong()).isEqualTo(101);
        assertThat(reply.at("/trace/0/ruleId").asString()).isEqualTo("tier-high");
        assertThat(reply.get("ruleSet").asString()).isEqualTo("loyalty.default@v8");
    }

    @Test
    void aNonQualifyingTransactionIsRecordedAsDeclined() throws Exception {
        loyalty();
        write("/v1/awards", "{\"accountId\":\"grace\",\"transaction\":{\"type\":\"TXN_SETTLED\",\"amountKes\":800}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.awarded").isEmpty());
        assertThat(count("AWARD_NOT_QUALIFIED")).isEqualTo(1);
        assertThat(available("grace", "LOYALTY_MPR")).isZero();
    }

    @Test
    void publishingIsALedgerEventAndVersionsAreImmutable() throws Exception {
        loyalty();
        assertThat(count("RULE_VERSION_PUBLISHED")).isEqualTo(1);
        write("/v1/rules/versions", "{\"document\":" + LOYALTY_V7 + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_EXISTS"));
        JsonNode catalogue = read("/v1/rules");
        assertThat(catalogue.at("/ruleSets/0/activeVersion").asString()).isEqualTo("v7");
        assertThat(catalogue.get("overlapResolution").asString()).contains("priority");
    }

    @Test
    void aRuleThatTriesToRunCodeIsRefused() throws Exception {
        loyalty();
        String evil = LOYALTY_V7.replace("\"v7\"", "\"v9\"").replace(
                "event.type == 'TXN_SETTLED' && event.amountKes >= 1000",
                "T(java.lang.Runtime).getRuntime().exec('touch /tmp/pwned') != null");
        write("/v1/rules/versions", "{\"document\":" + evil + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").exists());
        write("/v1/rules/versions", "{\"document\":{\"ruleSetId\":\"x\",\"rules\":[]}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void simulationComparesAProposedRuleWithWhatWasAwarded() throws Exception {
        loyalty();
        award("john", 3_000);
        award("mary", 1_200);
        award("grace", 800);
        // "What if customers earned 2 tokens for every KES 1,000 spent?"
        String proposed = """
                {"ruleSetId":"loyalty.default","version":"v8-draft","effectiveFrom":"2027-01-01T00:00:00Z",
                 "rules":[{"id":"double","when":"event.type == 'TXN_SETTLED'",
                   "then":{"award":{"tokenId":"LOYALTY_MPR","units":"event.amountKes / 1000 * 2"}}}]}""";
        JsonNode result = body(write("/v1/rules/simulate", "{\"document\":" + proposed + "}")
                .andExpect(status().isOk()));
        assertThat(result.get("eventsEvaluated").asInt()).isEqualTo(3);
        assertThat(result.get("actualUnits").asLong()).isEqualTo(2);       // 1 + 1 + 0
        assertThat(result.get("simulatedUnits").asLong()).isEqualTo(8);    // 6 + 2 + 0
        assertThat(result.get("eventsChanged").asInt()).isEqualTo(2);
        assertThat(count("RULE_VERSION_PUBLISHED")).isEqualTo(1);           // nothing was published
    }

    @Test
    void evaluateShowsWhatADraftWouldAwardWithoutRecordingAnything() throws Exception {
        loyalty();
        long before = jdbc.queryForObject("SELECT count(*) FROM action_event", Long.class);
        JsonNode current = body(write("/v1/rules/evaluate",
                "{\"transaction\":{\"type\":\"TXN_SETTLED\",\"amountKes\":60000}}").andExpect(status().isOk()));
        assertThat(current.at("/awarded/LOYALTY_MPR").asLong()).isEqualTo(100);
        assertThat(current.get("ruleSet").asString()).isEqualTo("loyalty.default@v7");
        String draft = LOYALTY_V7.replace("\"v7\"", "\"v8\"").replace("\"priority\":10,\"stopOnMatch\":true", "\"priority\":10");
        JsonNode proposed = body(write("/v1/rules/evaluate", "{\"document\":" + draft
                + ",\"transaction\":{\"type\":\"TXN_SETTLED\",\"amountKes\":60000}}").andExpect(status().isOk()));
        assertThat(proposed.at("/awarded/LOYALTY_MPR").asLong()).isEqualTo(101);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_event", Long.class)).isEqualTo(before);
    }

    @Test
    void redeemingPaysOutAtTheRateInForce() throws Exception {
        loyalty();
        award("john", 60_000);   // 100 points under v7

        // No version sets a rate yet, so the token's own terms apply: KES 10 a point.
        JsonNode r = body(write("/v1/redemptions", "{\"tokenId\":\"LOYALTY_MPR\",\"accountId\":\"john\",\"units\":10}")
                .andExpect(status().isCreated()));
        assertThat(r.get("kesPerUnit").asLong()).isEqualTo(10);
        assertThat(r.get("valueKes").asLong()).isEqualTo(100);
        assertThat(r.get("rateSource").asString()).isEqualTo("token terms");
        String id = r.get("redemptionId").asString();
        assertThat(read("/v1/redemptions/" + id).get("payout").get("status").asString()).isEqualTo("PENDING");

        outbox.pollOnce();   // after the commit, the payout is asked of the PSP
        String paid = json.writeValueAsString(java.util.Map.of("pspEventId", "evt-b2c-1", "redemptionId", id,
                "status", "SUCCEEDED", "amountKes", 100, "pspReference", "B2C-TEST"));
        signedPost("/v1/payments/payout-callback", paid).andExpect(status().isOk());
        signedPost("/v1/payments/payout-callback", paid).andExpect(status().isOk());   // a resend changes nothing
        JsonNode receipt = read("/v1/redemptions/" + id);
        assertThat(receipt.get("payout").get("status").asString()).isEqualTo("PAID");
        assertThat(receipt.get("payout").get("pspReference").asString()).isEqualTo("B2C-TEST");
        assertThat(count("PAYOUT_SENT")).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/payments/payout-callback")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(paid))
                .andExpect(status().isUnauthorized());   // only the PSP, by its signature, may confirm a payout

        // v8 puts the rate in the rules: later redemptions use it and name the version that set it.
        String v8 = LOYALTY_V7.replace("\"v7\"", "\"v8\"").replace("2020-01-01T00:00:00Z", "2020-06-01T00:00:00Z")
                .replaceFirst("\"rules\"", "\"redemption\":{\"kesPerUnit\":12},\"rules\"");
        write("/v1/rules/versions", "{\"document\":" + v8 + "}").andExpect(status().isCreated());
        JsonNode r2 = body(write("/v1/redemptions", "{\"tokenId\":\"LOYALTY_MPR\",\"accountId\":\"john\",\"units\":5}")
                .andExpect(status().isCreated()));
        assertThat(r2.get("valueKes").asLong()).isEqualTo(60);
        assertThat(r2.get("rateSource").asString()).isEqualTo("loyalty.default@v8");
        assertThat(read("/v1/redemptions/" + r2.get("redemptionId").asString()).get("ruleVersion").asString())
                .isEqualTo("loyalty.default@v8");
        assertThat(read("/v1/tokens/LOYALTY_MPR").get("redemption").get("kesPerUnit").asLong()).isEqualTo(12);

        // A rate must be a positive whole number of shillings.
        write("/v1/rules/versions", "{\"document\":" + v8.replace("\"v8\"", "\"v9\"").replace("\"kesPerUnit\":12",
                "\"kesPerUnit\":0") + "}").andExpect(status().isBadRequest());
    }
}
