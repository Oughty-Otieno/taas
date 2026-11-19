package com.taas.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for the token operations. Every one carries a caller-chosen {@code txId}: the same txId
 * with the same request returns the original result instead of moving tokens twice.
 */
public final class Commands {

    private Commands() {
    }

    public record CreateToken(
            @NotBlank @Size(max = 100) String txId,
            @NotBlank String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull String kind,
            @Positive Long kesPerToken,
            @Positive Integer reservationMinutes,
            @Positive Long maxSupply) {
    }

    public record Issue(@NotBlank @Size(max = 100) String txId, @NotBlank String to, @Positive long amount,
            @Size(max = 200) String reference) {
    }

    public record Earn(@NotBlank @Size(max = 100) String txId, @NotBlank String account, @Positive long spendKes,
            @Size(max = 200) String reference) {
    }

    public record Transfer(@NotBlank @Size(max = 100) String txId, @NotBlank String from, @NotBlank String to,
            @Positive long amount, @Size(max = 200) String reference) {
    }

    public record Reserve(@NotBlank @Size(max = 100) String txId, @NotBlank String seller, @NotBlank String buyer,
            @Positive long amount) {
    }

    public record Purchase(@NotBlank @Size(max = 100) String txId, @NotBlank String buyer,
            @NotBlank @Size(max = 200) String paymentRef) {
    }

    public record Release(@NotBlank @Size(max = 100) String txId) {
    }

    public record Redeem(@NotBlank @Size(max = 100) String txId, @NotBlank String account, @Positive long amount,
            @Size(max = 200) String reference) {
    }
}
