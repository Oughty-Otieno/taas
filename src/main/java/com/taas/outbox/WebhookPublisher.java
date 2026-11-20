package com.taas.outbox;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.taas.api.ActionView;
import com.taas.common.Hashes;
import com.taas.ledger.ActionEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * Signed webhooks to partner systems. Each delivery carries
 * {@code X-TaaS-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}; receivers verify the
 * signature, refuse stale timestamps, and de-duplicate on the action id.
 */
@Component
public class WebhookPublisher implements OutboxSubscriber {

    public static final String SIGNATURE_HEADER = "X-TaaS-Signature";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<String> urls;
    private final String secret;
    private final ObjectMapper json;
    private final Clock clock;

    public WebhookPublisher(@Value("${taas.webhooks.urls:}") List<String> urls,
            @Value("${taas.webhooks.secret}") String secret, ObjectMapper json, Clock clock) {
        this.urls = urls.stream().filter(u -> !u.isBlank()).toList();
        this.secret = secret;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void onEvent(ActionEvent event) throws Exception {
        if (urls.isEmpty()) {
            return;
        }
        String body = json.writeValueAsString(ActionView.of(event));
        String header = signatureHeader(clock.instant().getEpochSecond(), body);
        for (String url : urls) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header(SIGNATURE_HEADER, header)
                    .header("X-TaaS-Event-Id", event.id().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        }
    }

    public String signatureHeader(long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + Hashes.hmacSha256Hex(secret, timestamp + "." + body);
    }
}
