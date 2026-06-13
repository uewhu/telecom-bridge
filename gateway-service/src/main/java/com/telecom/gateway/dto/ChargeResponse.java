package com.telecom.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound REST response for POST /api/v1/charge.
 */
@Schema(description = "Online charge response from the Diameter Ro/Gy interface")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeResponse(

    @Schema(description = "Echo of the session identifier", example = "sess-abc-001")
    String sessionId,

    @Schema(description = "Diameter Result-Code from the CCA", example = "2001")
    long resultCode,

    @Schema(description = "Number of units granted by the server", example = "1048576")
    long grantedUnits,

    @Schema(description = "High-level status", example = "SUCCESS",
            allowableValues = {"SUCCESS", "DENIED", "ERROR", "TIMEOUT"})
    String status,

    @Schema(description = "Human-readable message (present on error/timeout only)")
    String message
) {

    // ──────────────────────────────────────────────────────────────────────────
    // Factory methods
    // ──────────────────────────────────────────────────────────────────────────

    public static ChargeResponse success(String sessionId, long resultCode, long grantedUnits) {
        return new ChargeResponse(sessionId, resultCode, grantedUnits, "SUCCESS", null);
    }

    public static ChargeResponse denied(String sessionId, long resultCode) {
        return new ChargeResponse(sessionId, resultCode, 0, "DENIED",
                "Charge denied by Diameter server (Result-Code=" + resultCode + ")");
    }

    public static ChargeResponse timeout(String sessionId) {
        return new ChargeResponse(sessionId, -1, 0, "TIMEOUT",
                "Diameter server did not respond within the configured timeout.");
    }

    public static ChargeResponse error(String sessionId, String msg) {
        return new ChargeResponse(sessionId, -1, 0, "ERROR", msg);
    }
}
