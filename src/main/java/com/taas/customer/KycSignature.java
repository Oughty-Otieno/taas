package com.taas.customer;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.taas.common.CallbackSignature;

/** The identity-verification provider's callback signature, in {@code X-KYC-Signature} and {@code X-KYC-Timestamp}. */
@Component
public class KycSignature extends CallbackSignature {

    public static final String SIGNATURE_HEADER = "X-KYC-Signature";
    public static final String TIMESTAMP_HEADER = "X-KYC-Timestamp";

    public KycSignature(@Value("${taas.kyc.callback-secret}") String secret, Clock clock) {
        super(secret, clock);
    }
}
