package com.taas.api;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taas.common.InvalidRequest;
import com.taas.payment.PaymentSignature;
import com.taas.workflow.Commands;
import com.taas.workflow.WorkflowEngine;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The PSP webhook. Authenticated by its HMAC signature over the raw body, not by OAuth; replay-safe because the
 * signature is time-bound and the PSP event id is the idempotency key.
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final WorkflowEngine engine;
    private final PaymentSignature signature;
    private final ObjectMapper json;
    private final Validator validator;

    public PaymentController(WorkflowEngine engine, PaymentSignature signature, ObjectMapper json,
            Validator validator) {
        this.engine = engine;
        this.signature = signature;
        this.json = json;
        this.validator = validator;
    }

    @PostMapping("/callback")
    public ResponseEntity<Object> callback(
            @RequestHeader(value = PaymentSignature.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = PaymentSignature.SIGNATURE_HEADER, required = false) String sig,
            @RequestBody String body) {
        if (!signature.verify(timestamp, sig, body)) {
            return ResponseEntity.status(401).body(java.util.Map.of("title", "Unauthorized", "status", 401,
                    "detail", "Missing, invalid or stale PSP signature"));
        }
        Commands.PaymentCallback callback;
        try {
            callback = json.readValue(body, Commands.PaymentCallback.class);
        } catch (JacksonException e) {
            throw new InvalidRequest("Callback body is not valid JSON for a payment callback");
        }
        Set<ConstraintViolation<Commands.PaymentCallback>> violations = validator.validate(callback);
        if (!violations.isEmpty()) {
            throw new InvalidRequest(violations.stream().map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted().reduce((a, b) -> a + "; " + b).orElse("Invalid callback"));
        }
        return Http.send(engine.paymentCallback(callback));
    }
}
