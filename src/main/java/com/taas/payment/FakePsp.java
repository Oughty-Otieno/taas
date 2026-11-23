package com.taas.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * A stand-in payment provider (an M-PESA STK push, say). It accepts a charge, then calls back through the
 * gateway with a signed webhook after a short delay, as a real PSP would. {@code simulateOutcome} on the order
 * picks the path for a demo: SUCCESS, FAIL, NO_CALLBACK (the customer never pays, so the timer wins), or MANUAL:
 * the prompt waits on the buyer's "phone" until they answer it with {@link #answer}, as a real STK push waits for
 * the customer's PIN.
 */
@Component
public class FakePsp implements PaymentGateway, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FakePsp.class);

    private final Map<String, String> chargesByKey = new ConcurrentHashMap<>();
    /** MANUAL charges whose prompt the buyer has not answered yet, by order. */
    private final Map<java.util.UUID, Charge> prompts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final PaymentSignature signature;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean autoCallback;
    private final long delayMs;
    private final String callbackUrl;

    public FakePsp(PaymentSignature signature, ObjectMapper json, Clock clock,
            @Value("${taas.payments.fake-psp.auto-callback}") boolean autoCallback,
            @Value("${taas.payments.fake-psp.callback-delay-ms}") long delayMs,
            @Value("${taas.payments.fake-psp.callback-url}") String callbackUrl) {
        this.signature = signature;
        this.json = json;
        this.clock = clock;
        this.autoCallback = autoCallback;
        this.delayMs = delayMs;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public String charge(Charge charge) {
        boolean[] isNew = {false};
        String reference = chargesByKey.computeIfAbsent(charge.orderId() + "/" + charge.idempotencyKey(), k -> {
            isNew[0] = true;
            return "PSP-" + Long.toString(Math.abs((long) k.hashCode()) + clock.millis(), 36).toUpperCase();
        });
        if (isNew[0] && "MANUAL".equals(charge.simulateOutcome())) {
            prompts.put(charge.orderId(), charge);
        } else if (isNew[0] && autoCallback && !"NO_CALLBACK".equals(charge.simulateOutcome())) {
            scheduler.schedule(() -> callBack(charge, reference, charge.simulateOutcome()), delayMs,
                    TimeUnit.MILLISECONDS);
        }
        return reference;
    }

    /** Whether a MANUAL prompt for this order is still waiting for the buyer. */
    public boolean prompting(java.util.UUID orderId) {
        return prompts.containsKey(orderId);
    }

    /**
     * The buyer answers the prompt: pay, or cancel it. The verdict reaches the platform the way any PSP verdict
     * does, through the signed webhook. Returns false when no prompt is waiting (answered already, or never sent).
     */
    public boolean answer(java.util.UUID orderId, boolean pay) {
        Charge charge = prompts.remove(orderId);
        if (charge == null) {
            return false;
        }
        String reference = chargesByKey.get(charge.orderId() + "/" + charge.idempotencyKey());
        if (autoCallback) {
            scheduler.schedule(() -> callBack(charge, reference, pay ? "SUCCESS" : "FAIL"), 300, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    private void callBack(Charge charge, String reference, String outcome) {
        boolean fail = "FAIL".equals(outcome);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pspEventId", "evt-" + reference);
        body.put("orderId", charge.orderId());
        body.put("status", fail ? "FAILED" : "SUCCEEDED");
        body.put("amountKes", charge.amountKes());
        body.put("pspReference", reference);
        if (fail) {
            body.put("reason", "Customer cancelled the M-PESA prompt");
        }
        String text = json.writeValueAsString(body);
        long ts = clock.instant().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(callbackUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(PaymentSignature.TIMESTAMP_HEADER, Long.toString(ts))
                .header(PaymentSignature.SIGNATURE_HEADER, signature.sign(ts, text))
                .POST(HttpRequest.BodyPublishers.ofString(text))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("PSP callback for order {} -> {}", charge.orderId(), response.statusCode());
        } catch (Exception e) {
            log.warn("PSP callback for order {} failed: {}", charge.orderId(), e.toString());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
