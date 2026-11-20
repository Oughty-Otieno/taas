package com.taas.payment;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.taas.common.CallbackSignature;

/** The PSP callback's signature, in {@code X-PSP-Signature} and {@code X-PSP-Timestamp}. mTLS in production. */
@Component
public class PaymentSignature extends CallbackSignature {

    public static final String SIGNATURE_HEADER = "X-PSP-Signature";
    public static final String TIMESTAMP_HEADER = "X-PSP-Timestamp";

    public PaymentSignature(@Value("${taas.payments.callback-secret}") String secret, Clock clock) {
        super(secret, clock);
    }
}
