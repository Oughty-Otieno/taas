package com.taas.workflow;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

/**
 * Request bodies. None carries an idempotency key: that is the {@code Idempotency-Key} header. None lets a
 * loyalty client name an amount: an award submits the qualifying transaction and the rules decide.
 */
public final class Commands {

    private Commands() {
    }

    public record DefineToken(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_]{1,31}") String tokenId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "LOYALTY|ASSET") String kind,
            @Size(max = 60) String assetType,
            @Size(max = 300) String backing,
            @Positive Long valuationKes,
            @Positive Long unitPriceKes,
            @PositiveOrZero Long redeemValueKes,
            @NotNull @Positive Long authorisedSupply,
            @Min(1) @Max(100) Integer maxHolderPercent,
            @Min(1) @Max(1440) Integer reservationMinutes,
            Boolean kycRequired,
            @Min(1) @Max(120) Integer inactivityMonths,
            @Size(max = 64) String ruleSetId) {
    }

    public record Issue(@NotBlank String tokenId, @Positive long units, @Size(max = 200) String reference) {
    }

    /** A qualifying business event, such as a settled card or M-PESA transaction. */
    public record Transaction(
            @NotBlank @Size(max = 40) String type,
            @PositiveOrZero long amountKes,
            @Size(max = 64) String merchantId,
            @Size(max = 20) String channel,
            @Size(max = 200) String reference) {
    }

    public record Award(@NotBlank String accountId, @Size(max = 64) String ruleSetId,
            @NotNull @Valid Transaction transaction) {
    }

    public record Reserve(@NotBlank String tokenId, @NotBlank String accountId, @Positive long units,
            @Min(10) @Max(86400) Integer holdSeconds) {
    }

    public record PlaceOrder(@NotNull UUID reservationId, @Size(max = 20) String paymentMethod,
            @Pattern(regexp = "SUCCESS|FAIL|NO_CALLBACK") String simulateOutcome) {
    }

    public record Transfer(@NotBlank String tokenId, @NotBlank String fromAccountId, @NotBlank String toAccountId,
            @Positive long units, @Size(max = 200) String reference) {
    }

    public record Redeem(@NotBlank String tokenId, @NotBlank String accountId, @Positive long units,
            @Size(max = 64) String merchantId, @Size(max = 200) String reference) {
    }

    public record PublishRules(@NotNull JsonNode document) {
    }

    /** The PSP's webhook body. Its authenticity is the signature header, checked before this is read. */
    public record PaymentCallback(
            @NotBlank @Size(max = 100) String pspEventId,
            @NotNull UUID orderId,
            @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status,
            @PositiveOrZero long amountKes,
            @Size(max = 100) String pspReference,
            @Size(max = 200) String reason) {
    }
}
