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
}
