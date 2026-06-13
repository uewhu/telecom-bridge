package com.telecom.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Standard error envelope for REST error responses.
 */
@Schema(description = "API error response")
public record ErrorResponse(

    @Schema(description = "HTTP status code", example = "400")
    int status,

    @Schema(description = "Error category", example = "VALIDATION_ERROR")
    String error,

    @Schema(description = "Human-readable description")
    String message,

    @Schema(description = "Request path", example = "/api/v1/charge")
    String path
) {}
