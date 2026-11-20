package com.taas.customer;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CustomerCommands {

    private CustomerCommands() {
    }

    /** Onboarding. The personal data goes to the PII store; the ledger records only a digest. */
    public record Register(
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String accountId,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{9,15}", message = "must be 9-15 digits, optionally starting with +")
            String phone,
            @NotBlank @Pattern(regexp = "[0-9A-Za-z]{5,20}", message = "must be 5-20 letters or digits") String nationalId,
            LocalDate dateOfBirth) {
    }

    /** Tier 1: identity. Tier 2: identity, address and source of funds. */
    public record SubmitKyc(@NotNull @Min(1) @Max(2) Integer tier) {
    }

    /** The identity-verification provider's verdict, delivered by signed webhook. */
    public record KycCallback(
            @NotBlank @Size(max = 100) String providerEventId,
            @NotNull UUID caseId,
            @NotBlank String accountId,
            @NotBlank @Pattern(regexp = "VERIFIED|REJECTED|REFERRED") String result,
            @Min(1) @Max(2) Integer tier,
            @Size(max = 300) String reason) {
    }

    /** A compliance officer's decision on a referred case, or a revocation. */
    public record Decision(
            @NotBlank @Pattern(regexp = "VERIFY|REJECT|REVOKE") String decision,
            @Min(1) @Max(2) Integer tier,
            @Size(max = 300) String reason) {
    }
}
