package com.taas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.taas.ledger.Registry;

/** Businesses (issuers) with four-eyes verification, and named staff users managed by an admin. */
class BusinessStaffTest extends PlatformTestBase {

    private static final String RIVERSIDE = """
            {"businessId":"riverside","name":"Riverside Properties Ltd","registrationNo":"PVT-1","sector":"Real estate",
             "payoutAccount":"Paybill 522011"}""";

    @Autowired Registry registry;

    private int n;

    @Test
    void anAdminRegistersABusinessAndADifferentOfficerVerifiesIt() throws Exception {
        String amina = user("amina.yusuf");
        as(amina, post("/v1/businesses"), RIVERSIDE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
        as(amina, post("/v1/businesses"), RIVERSIDE.replace("riverside\"", "riverside-2\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRATION_EXISTS"));

        // Four eyes: the registrar cannot verify, even with the scope to do so.
        String both = issuer.issue("amina.yusuf", java.util.List.of("business:review"), java.time.Duration.ofHours(1));
        as(both, post("/v1/businesses/riverside/decision"), "{\"decision\":\"VERIFY\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FOUR_EYES"));
        // Only compliance holds business:review.
        as(amina, post("/v1/businesses/riverside/decision"), "{\"decision\":\"VERIFY\"}")
                .andExpect(status().isForbidden());
        as(user("david.mwangi"), post("/v1/businesses/riverside/decision"), "{\"decision\":\"VERIFY\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));

        assertThat(read("/v1/businesses/riverside").at("/business/status").asString()).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT payload->>'decidedBy' FROM action_event WHERE event_type ="
                + " 'BUSINESS_VERIFIED'", String.class)).isEqualTo("david.mwangi");
    }

    @Test
    void assetsAreListedOnlyUnderAVerifiedBusiness() throws Exception {
        write("/v1/businesses", RIVERSIDE).andExpect(status().isCreated());
        String apt = """
                {"tokenId":"RWA_APT009","name":"Riverside Apartment 009","kind":"ASSET","assetType":"Apartment",
                 "authorisedSupply":10000,"unitPriceKes":25,"issuerId":"riverside"}""";
        write("/v1/tokens", apt).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ISSUER_NOT_VERIFIED"));

        verify("riverside");
        write("/v1/tokens", apt).andExpect(status().isCreated());

        JsonAssert tokens = new JsonAssert(read("/v1/tokens"));
        assertThat(tokens.node.get(0).at("/issuer/name").asString()).isEqualTo("Riverside Properties Ltd");
        var detail = read("/v1/businesses/riverside");
        assertThat(detail.at("/tokens/0/tokenId").asString()).isEqualTo("RWA_APT009");
        assertThat(detail.get("history").toString()).contains("TOKEN_DEFINED", "BUSINESS_VERIFIED",
                "BUSINESS_REGISTERED");
    }

    @Test
    void aSuspendedIssuerCannotIssueOrSellButHoldersKeepTheirRights() throws Exception {
        write("/v1/businesses", RIVERSIDE).andExpect(status().isCreated());
        verify("riverside");
        asset("RWA_APT001");
        write("/v1/tokens/RWA_APT001/issuer", "{\"issuerId\":\"riverside\"}").andExpect(status().isOk());
        buy("RWA_APT001", "alice", 100);

        as(persona("compliance"), post("/v1/businesses/riverside/decision"), "{\"decision\":\"SUSPEND\"}")
                .andExpect(status().isBadRequest());   // a reason is required
        as(persona("compliance"), post("/v1/businesses/riverside/decision"),
                "{\"decision\":\"SUSPEND\",\"reason\":\"Title dispute under review\"}").andExpect(status().isOk());

        write("/v1/issuances", "{\"tokenId\":\"RWA_APT001\",\"units\":1}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ISSUER_SUSPENDED"));
        write("/v1/reservations", "{\"tokenId\":\"RWA_APT001\",\"accountId\":\"bob\",\"units\":10}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ISSUER_SUSPENDED"));
        write("/v1/transfers", "{\"tokenId\":\"RWA_APT001\",\"fromAccountId\":\"alice\",\"toAccountId\":\"bob\","
                + "\"units\":10}").andExpect(status().isCreated());

        verify("riverside");   // reinstated
        reserve("RWA_APT001", "bob", 10);
    }

    @Test
    void anIssuerCanBeNamedForAnExistingTokenAndReplayRebuildsIt() throws Exception {
        asset("RWA_TWR014");
        write("/v1/businesses", RIVERSIDE).andExpect(status().isCreated());
        write("/v1/tokens/RWA_TWR014/issuer", "{\"issuerId\":\"riverside\"}")
                .andExpect(status().isUnprocessableContent());   // not verified yet
        verify("riverside");
        write("/v1/tokens/RWA_TWR014/issuer", "{\"issuerId\":\"riverside\"}").andExpect(status().isOk());
        write("/v1/tokens/RWA_TWR014/issuer", "{\"issuerId\":\"riverside\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUER_ALREADY_SET"));

        projector.wipeProjections();
        assertThat(registry.businesses()).isEmpty();
        var report = projector.replay();
        assertThat(report.holdingsIdentical()).isTrue();
        assertThat(report.workflowIdentical()).isTrue();
        assertThat(registry.issuerOf("RWA_TWR014")).map(Registry.Business::status)
                .contains(Registry.BusinessStatus.VERIFIED);
    }

    @Test
    void staffUsersActUnderTheirOwnNameAndTheirEmailStaysOutOfTheLedger() throws Exception {
        loyalty();
        as(user("amina.yusuf"), post("/v1/staff"),
                "{\"displayName\":\"Jane Wambui\",\"email\":\"jane@example.com\",\"role\":\"operations\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.userId").value("jane.wambui"));

        as(user("jane.wambui"), post("/v1/awards"), "{\"accountId\":\"john\",\"transaction\":{\"type\":\"TXN_SETTLED\","
                + "\"amountKes\":5000}}").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT actor_id FROM action_event WHERE event_type = 'TOKEN_EARNED'",
                String.class)).isEqualTo("jane.wambui");
        assertThat(jdbc.queryForObject("SELECT payload->>'createdBy' FROM action_event WHERE event_type ="
                + " 'STAFF_USER_CREATED'", String.class)).isEqualTo("amina.yusuf");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM action_event WHERE payload::text LIKE '%jane@example%'",
                Long.class)).isZero();

        // Operations cannot manage staff.
        as(user("jane.wambui"), get("/v1/staff"), null).andExpect(status().isForbidden());
    }

    @Test
    void disablingAUserOrChangingTheirRoleStopsTheirTokenAtOnce() throws Exception {
        String admin = user("amina.yusuf");
        String faith = user("faith.chebet");
        as(faith, get("/v1/tokens"), null).andExpect(status().isOk());

        as(admin, post("/v1/staff/faith.chebet/status"), "{\"status\":\"DISABLED\"}")
                .andExpect(status().isBadRequest());   // a reason is required
        as(admin, post("/v1/staff/faith.chebet/status"), "{\"status\":\"DISABLED\",\"reason\":\"Left the company\"}")
                .andExpect(status().isOk());
        as(faith, get("/v1/tokens"), null).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/dev/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"faith.chebet\"}")).andExpect(status().isBadRequest());

        as(admin, post("/v1/staff/faith.chebet/status"), "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());
        String again = user("faith.chebet");
        as(again, get("/v1/tokens"), null).andExpect(status().isOk());

        as(admin, post("/v1/staff/faith.chebet/role"), "{\"role\":\"auditor\"}").andExpect(status().isOk());
        as(again, get("/v1/tokens"), null).andExpect(status().isUnauthorized());   // old role's token
        assertThat(count("STAFF_USER_DISABLED")).isEqualTo(1);
        assertThat(count("STAFF_ROLE_CHANGED")).isEqualTo(1);
    }

    @Test
    void nobodyChangesThemselvesAndTheLastAdminStays() throws Exception {
        String amina = user("amina.yusuf");
        as(amina, post("/v1/staff/amina.yusuf/status"), "{\"status\":\"DISABLED\",\"reason\":\"test\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CANNOT_CHANGE_SELF"));
        // Another holder of staff:manage cannot remove the only admin either.
        write("/v1/staff/amina.yusuf/role", "{\"role\":\"auditor\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));

        as(amina, post("/v1/staff"), "{\"userId\":\"otieno.admin\",\"displayName\":\"Otieno\",\"role\":\"admin\"}")
                .andExpect(status().isCreated());
        as(user("otieno.admin"), post("/v1/staff/amina.yusuf/status"),
                "{\"status\":\"DISABLED\",\"reason\":\"Handover\"}").andExpect(status().isOk());
        write("/v1/staff/otieno.admin/status", "{\"status\":\"DISABLED\",\"reason\":\"test\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        assertThat(count("REQUEST_REJECTED")).isEqualTo(3);
    }

    @Test
    void theSignInMenuListsActiveUsersOnly() throws Exception {
        write("/v1/staff/lucy.atieno/status", "{\"status\":\"DISABLED\",\"reason\":\"On leave\"}")
                .andExpect(status().isOk());
        String users = body(mvc.perform(get("/v1/dev/users")).andExpect(status().isOk())).toString();
        assertThat(users).contains("amina.yusuf", "Compliance officer").doesNotContain("lucy.atieno");
    }

    // ---------------------------------------------------------------------------------------------

    private void verify(String businessId) throws Exception {
        as(persona("compliance"), post("/v1/businesses/" + businessId + "/decision"), "{\"decision\":\"VERIFY\"}")
                .andExpect(status().isOk());
    }

    private String user(String userId) throws Exception {
        return body(mvc.perform(post("/v1/dev/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}")).andExpect(status().isOk())).get("access_token").asString();
    }

    private String persona(String persona) throws Exception {
        return body(mvc.perform(post("/v1/dev/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"persona\":\"" + persona + "\"}")).andExpect(status().isOk())).get("access_token")
                .asString();
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + token).header("Idempotency-Key", "b-" + (++n));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    @Test
    void anAssetsValuationIsItsUnitsTimesItsPrice() throws Exception {
        write("/v1/tokens", "{\"tokenId\":\"RWA_BAD\",\"name\":\"Mismatch\",\"kind\":\"ASSET\","
                + "\"authorisedSupply\":1000,\"unitPriceKes\":25,\"valuationKes\":30000}")
                .andExpect(status().isBadRequest());
        write("/v1/tokens", "{\"tokenId\":\"RWA_OK\",\"name\":\"Derived\",\"kind\":\"ASSET\","
                + "\"authorisedSupply\":1000,\"unitPriceKes\":25}").andExpect(status().isCreated());
        assertThat(registry.token("RWA_OK").orElseThrow().valuationKes()).isEqualTo(25_000L);
        assertThat(registry.token("RWA_BAD")).isEmpty();
    }

    private record JsonAssert(tools.jackson.databind.JsonNode node) {
    }
}
