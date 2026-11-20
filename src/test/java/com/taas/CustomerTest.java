package com.taas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.taas.customer.FakeKycProvider;
import com.taas.customer.KycSignature;
import com.taas.ledger.ActionLedger;

import tools.jackson.databind.JsonNode;

/** Sign-in roles, account binding, onboarding and KYC. */
class CustomerTest extends PlatformTestBase {

    @Autowired KycSignature kycSignature;
    @Autowired FakeKycProvider provider;
    @Autowired ActionLedger actions;

    private int n;

    // --- Sign-in roles and account binding ---------------------------------------------------------

    @Test
    void aCustomerActsOnlyForTheirOwnAccount() throws Exception {
        loyalty();
        award("john", 60_000);
        award("alice", 60_000);
        String john = persona("customer", "john");

        as(john, post("/v1/transfers"), "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"alice\",\"toAccountId\":\"john\",\"units\":5}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_PERMITTED"));
        assertThat(count("REQUEST_REJECTED")).isEqualTo(1);   // the deny is a ledger event
        as(john, post("/v1/transfers"), "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"john\",\"toAccountId\":\"alice\",\"units\":5}")
                .andExpect(status().isCreated());

        mvc.perform(get("/v1/accounts/john/portfolio").header("Authorization", "Bearer " + john)).andExpect(status().isOk());
        mvc.perform(get("/v1/accounts/alice/portfolio").header("Authorization", "Bearer " + john))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/ledger/actions").header("Authorization", "Bearer " + john)).andExpect(status().isForbidden());
        JsonNode accounts = body(mvc.perform(get("/v1/accounts").header("Authorization", "Bearer " + john)));
        assertThat(accounts.size()).isEqualTo(1);
    }

    @Test
    void eachRoleHasOnlyItsScopes() throws Exception {
        asset("RWA_APT001");
        as(persona("auditor", null), post("/v1/issuances"), "{\"tokenId\":\"RWA_APT001\",\"units\":1}")
                .andExpect(status().isForbidden());
        as(persona("compliance", null), post("/v1/reservations"), "{\"tokenId\":\"RWA_APT001\",\"accountId\":\"alice\",\"units\":1}")
                .andExpect(status().isForbidden());
        as(persona("rules-manager", null), post("/v1/rules/versions"), "{\"document\":" + LOYALTY_V7 + "}")
                .andExpect(status().isBadRequest());   // allowed through; refused only because LOYALTY_MPR does not exist
        mvc.perform(post("/v1/dev/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"persona\":\"customer\",\"accountId\":\"nobody\"}")).andExpect(status().isBadRequest());
    }

    // --- Onboarding and KYC ------------------------------------------------------------------------

    @Test
    void onboardingThenTierOneKycThroughTheProvider() throws Exception {
        asset("RWA_APT001");
        String ops = persona("operations", null);
        JsonNode reg = body(as(ops, post("/v1/customers"), """
                {"accountId":"wanjiku","fullName":"Wanjiku Mwangi","phone":"+254712345678","nationalId":"28765432",
                 "dateOfBirth":"1990-04-12"}""").andExpect(status().isCreated()));
        assertThat(reg.get("kycStatus").asString()).isEqualTo("NONE");

        // Not yet eligible to hold an asset.
        as(ops, post("/v1/reservations"), "{\"tokenId\":\"RWA_APT001\",\"accountId\":\"wanjiku\",\"units\":10}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("KYC_REQUIRED"));

        as(ops, post("/v1/customers/wanjiku/kyc"), "{\"tier\":1}").andExpect(status().isAccepted());
        as(ops, post("/v1/customers/wanjiku/kyc"), "{\"tier\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KYC_IN_PROGRESS"));
        providerAnswers("wanjiku").andExpect(status().isOk()).andExpect(jsonPath("$.kycStatus").value("VERIFIED"));

        // Tier 1: up to KES 100,000 per purchase (here 250 x KES 25 = 6,250).
        as(ops, post("/v1/reservations"), "{\"tokenId\":\"RWA_APT001\",\"accountId\":\"wanjiku\",\"units\":250}")
                .andExpect(status().isCreated());
        as(ops, post("/v1/reservations"), "{\"tokenId\":\"RWA_APT001\",\"accountId\":\"wanjiku\",\"units\":1500}")
                .andExpect(status().isCreated());   // KES 37,500
        clock.advance(Duration.ofMinutes(16));
        timers.expireReservations();
        write("/v1/tokens", "{\"tokenId\":\"RWA_TWR014\",\"name\":\"Tower\",\"kind\":\"ASSET\",\"authorisedSupply\":10000,"
                + "\"unitPriceKes\":1200}");
        write("/v1/issuances", "{\"tokenId\":\"RWA_TWR014\",\"units\":10000}");
        as(ops, post("/v1/reservations"), "{\"tokenId\":\"RWA_TWR014\",\"accountId\":\"wanjiku\",\"units\":100}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("KYC_TIER_TOO_LOW"));  // KES 120,000

        assertThat(types("wanjiku")).containsSubsequence("CUSTOMER_REGISTERED", "KYC_SUBMITTED", "KYC_VERIFIED");
        // Personal data never reaches the ledger: only a digest.
        String ledger = String.join("\n", jdbc.queryForList("SELECT payload::text FROM action_event", String.class));
        assertThat(ledger).doesNotContain("28765432").doesNotContain("Wanjiku Mwangi").doesNotContain("254712345678");
    }

    @Test
    void tierTwoIsReferredAndACompianceOfficerDecides() throws Exception {
        register("kamau", "29876543");
        as(persona("operations", null), post("/v1/customers/kamau/kyc"), "{\"tier\":1}");
        providerAnswers("kamau");
        String kamau = persona("customer", "kamau");
        as(kamau, post("/v1/customers/kamau/kyc"), "{\"tier\":2}").andExpect(status().isAccepted());
        providerAnswers("kamau").andExpect(jsonPath("$.review").value("NEEDS_REVIEW"));
        JsonNode c = read("/v1/customers/kamau").get("customer");
        assertThat(c.get("kycStatus").asString()).isEqualTo("VERIFIED");   // still tier 1 while the case is open
        assertThat(c.get("kycTier").asInt()).isEqualTo(1);

        as(persona("operations", null), post("/v1/customers/kamau/kyc/decision"), "{\"decision\":\"VERIFY\"}")
                .andExpect(status().isForbidden());
        as(persona("compliance", null), post("/v1/customers/kamau/kyc/decision"),
                "{\"decision\":\"VERIFY\",\"reason\":\"Utility bill and payslips checked\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.kycTier").value(2));
        var decided = actions.list(new ActionLedger.Filter(null, null, "kamau", null, List.of("KYC_VERIFIED"),
                null, null, null), null, 1, true).get(0);
        assertThat(decided.get("decidedBy")).isEqualTo("officer:compliance@taas");
    }

    @Test
    void anUnknownIdIsRejectedByTheProvider() throws Exception {
        register("otieno", "00012345");
        as(persona("operations", null), post("/v1/customers/otieno/kyc"), "{\"tier\":1}");
        providerAnswers("otieno").andExpect(jsonPath("$.kycStatus").value("REJECTED"));
        assertThat(count("KYC_REJECTED")).isEqualTo(1);
    }

    @Test
    void revokingFreezesTheAccountButKeepsItsUnits() throws Exception {
        loyalty();
        award("alice", 60_000);
        as(persona("compliance", null), post("/v1/customers/alice/kyc/decision"), "{\"decision\":\"REVOKE\"}")
                .andExpect(status().isBadRequest());   // a reason is required
        as(persona("compliance", null), post("/v1/customers/alice/kyc/decision"),
                "{\"decision\":\"REVOKE\",\"reason\":\"Sanctions screening match under review\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.kycStatus").value("FROZEN"));

        write("/v1/transfers", "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"alice\",\"toAccountId\":\"bob\",\"units\":1}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_FROZEN"));
        award("john", 60_000);
        write("/v1/transfers", "{\"tokenId\":\"LOYALTY_MPR\",\"fromAccountId\":\"john\",\"toAccountId\":\"alice\",\"units\":1}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_FROZEN"));
        assertThat(available("alice", "LOYALTY_MPR")).isEqualTo(100);
        assertThat(count("KYC_REVOKED")).isEqualTo(1);
    }

    @Test
    void erasingPersonalDataKeepsTheLedgerDigest() throws Exception {
        register("achieng", "30123456");
        String compliance = persona("compliance", null);
        as(compliance, delete("/v1/customers/achieng/pii"), null).andExpect(status().isOk());
        JsonNode c = read("/v1/customers/achieng").get("customer");
        assertThat(c.get("piiErased").asBoolean()).isTrue();
        assertThat(c.get("phoneMasked").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT national_id FROM customer_pii WHERE account_id = 'achieng'", String.class))
                .isNull();
        List<String> digests = jdbc.queryForList("""
                SELECT payload->>'piiDigest' FROM action_event
                WHERE event_type IN ('CUSTOMER_REGISTERED', 'PII_ERASED') AND payload->>'accountId' = 'achieng'
                ORDER BY seq""", String.class);
        assertThat(digests).hasSize(2);
        assertThat(digests.get(0)).isEqualTo(digests.get(1));
        as(persona("operations", null), post("/v1/customers/achieng/kyc"), "{\"tier\":1}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("NO_PERSONAL_DATA"));
    }

    @Test
    void anUnsignedKycCallbackIsRefused() throws Exception {
        mvc.perform(post("/v1/kyc/callback").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("X-KYC-Timestamp", "1").header("X-KYC-Signature", "00")).andExpect(status().isUnauthorized());
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private void register(String accountId, String nationalId) throws Exception {
        as(persona("operations", null), post("/v1/customers"), "{\"accountId\":\"" + accountId + "\",\"fullName\":\""
                + accountId + " Test\",\"phone\":\"+254700000001\",\"nationalId\":\"" + nationalId + "\"}")
                .andExpect(status().isCreated());
    }

    /** The fake provider decides the latest open case, and its verdict arrives as a signed webhook. */
    private ResultActions providerAnswers(String accountId) throws Exception {
        var submitted = actions.list(new ActionLedger.Filter(null, null, accountId, null, List.of("KYC_SUBMITTED"),
                null, null, null), null, 1, true).get(0);
        Map<String, Object> verdict = provider.verdict(submitted);
        String body = json.writeValueAsString(verdict);
        long ts = clock.instant().getEpochSecond();
        return mvc.perform(post("/v1/kyc/callback").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(KycSignature.TIMESTAMP_HEADER, Long.toString(ts))
                .header(KycSignature.SIGNATURE_HEADER, kycSignature.sign(ts, body)));
    }

    private List<String> types(String accountId) {
        return jdbc.queryForList("SELECT event_type FROM action_event WHERE payload->>'accountId' = ? ORDER BY seq",
                String.class, accountId);
    }

    private String persona(String persona, String accountId) throws Exception {
        String req = accountId == null ? "{\"persona\":\"" + persona + "\"}"
                : "{\"persona\":\"" + persona + "\",\"accountId\":\"" + accountId + "\"}";
        return body(mvc.perform(post("/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isOk())).get("access_token").asString();
    }

    private ResultActions as(String token,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body)
            throws Exception {
        request.header("Authorization", "Bearer " + token).header("Idempotency-Key", "c-" + (++n));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }
}
