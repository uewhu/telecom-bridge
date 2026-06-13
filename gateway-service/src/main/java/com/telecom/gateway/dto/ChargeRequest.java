package com.telecom.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Inbound REST payload for POST /api/v1/charge.
 */
@Schema(description = "Online charge request payload")
public record ChargeRequest(

    @Schema(description = "Unique session identifier", example = "sess-abc-001")
    @NotBlank(message = "sessionId must not be blank")
    String sessionId,

    @Schema(description = "Subscriber MSISDN (E.164 format)", example = "447700900001")
    @NotBlank(message = "subscriberId must not be blank")
    String subscriberId,

    @Schema(description = "Service identifier (e.g. DATA, VOICE, SMS)", example = "DATA")
    @NotBlank(message = "serviceId must not be blank")
    String serviceId,

    @Schema(description = "Requested units (bytes for data, seconds for voice)", example = "1048576")
    @Positive(message = "requestedUnits must be positive")
    long requestedUnits
) {}
