package com.taas.customer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.taas.ledger.ActionEvent;
import com.taas.ledger.EventType;
import com.taas.outbox.OutboxSubscriber;

import tools.jackson.databind.ObjectMapper;

/**
 * A stand-in identity-verification provider (a national-registry check plus document review). It receives each
 * committed KYC_SUBMITTED from the outbox and answers later through the signed webhook, as a real provider would.
 *
 * <p>Demo behaviour: an ID number starting with 000 is not found in the registry (REJECTED); a tier-2 request
 * needs a person to review proof of address (REFERRED to compliance); anything else is VERIFIED.
 */
@Component
public class FakeKycProvider implements OutboxSubscriber, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FakeKycProvider.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final CustomerDirectory directory;
    private final KycSignature signature;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean autoCallback;
    private final long delayMs;
    private final String callbackUrl;

    public FakeKycProvider(CustomerDirectory directory, KycSignature signature, ObjectMapper json, Clock clock,
            @Value("${taas.kyc.fake-provider.auto-callback}") boolean autoCallback,
            @Value("${taas.kyc.fake-provider.callback-delay-ms}") long delayMs,
            @Value("${taas.kyc.fake-provider.callback-url}") String callbackUrl) {
        this.directory = directory;
        this.signature = signature;
        this.json = json;
        this.clock = clock;
        this.autoCallback = autoCallback;
        this.delayMs = delayMs;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public void onEvent(ActionEvent e) {
        if (e.type() != EventType.KYC_SUBMITTED || !autoCallback) {
            return;
        }
        Map<String, Object> verdict = verdict(e);
        scheduler.schedule(() -> send(verdict), delayMs, TimeUnit.MILLISECONDS);
    }

    /** What this provider decides for a submitted case. */
    public Map<String, Object> verdict(ActionEvent e) {
        String accountId = e.get("accountId");
        String nationalId = directory.pii(accountId).map(CustomerDirectory.Pii::nationalId).orElse("");
        int tier = Integer.parseInt(e.get("tierRequested"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerEventId", "kyc-" + e.get("caseId"));
        body.put("caseId", e.get("caseId"));
        body.put("accountId", accountId);
        if (nationalId == null || nationalId.startsWith("000")) {
            body.put("result", "REJECTED");
            body.put("reason", "ID number not found in the national registry");
        } else if (tier >= 2) {
            body.put("result", "REFERRED");
            body.put("tier", tier);
            body.put("reason", "Identity matched; proof of address and source of funds need manual review");
        } else {
            body.put("result", "VERIFIED");
            body.put("tier", 1);
            body.put("reason", "Identity matched the national registry and the M-PESA registered name");
        }
        return body;
    }

    private void send(Map<String, Object> verdict) {
        String text = json.writeValueAsString(verdict);
        long ts = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(callbackUrl)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(KycSignature.TIMESTAMP_HEADER, Long.toString(ts))
                .header(KycSignature.SIGNATURE_HEADER, signature.sign(ts, text))
                .POST(HttpRequest.BodyPublishers.ofString(text)).build();
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("KYC callback for {} -> {}", verdict.get("accountId"), r.statusCode());
        } catch (Exception ex) {
            log.warn("KYC callback for {} failed: {}", verdict.get("accountId"), ex.toString());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
