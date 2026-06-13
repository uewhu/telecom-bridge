package com.telecom.gateway.controller;

import com.telecom.gateway.dto.ChargeRequest;
import com.telecom.gateway.dto.ChargeResponse;
import com.telecom.gateway.dto.ErrorResponse;
import com.telecom.gateway.service.DiameterGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * REST controller for the Telecom-Bridge charge endpoint.
 *
 * <p>All handling is non-blocking. The reactive chain suspends the caller
 * until the Diameter CCA arrives, without holding any thread.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Charging", description = "Online Charging System (OCS) via Diameter Ro/Gy interface")
public class ChargeController {

    private static final Logger log = LoggerFactory.getLogger(ChargeController.class);

    private final DiameterGatewayService gatewayService;

    public ChargeController(DiameterGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // POST /api/v1/charge
    // ──────────────────────────────────────────────────────────────────────────

    @PostMapping(
        value    = "/charge",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary     = "Submit an online charge request",
        description = "Initiates an asynchronous Diameter CCR (Credit Control Request) "
                    + "to the OCS and returns the CCA result. "
                    + "The HTTP thread is released immediately — no blocking occurs."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Charge successful",
            content = @Content(schema = @Schema(implementation = ChargeResponse.class),
                examples = @ExampleObject(value = """
                    {"sessionId":"sess-001","resultCode":2001,
                     "grantedUnits":1048576,"status":"SUCCESS"}"""))),
        @ApiResponse(responseCode = "402", description = "Charge denied by OCS",
            content = @Content(schema = @Schema(implementation = ChargeResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request payload",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Diameter server unavailable",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "504", description = "Diameter response timed out",
            content = @Content(schema = @Schema(implementation = ChargeResponse.class)))
    })
    public Mono<ResponseEntity<ChargeResponse>> charge(
            @Valid @RequestBody ChargeRequest request,
            ServerWebExchange exchange) {

        log.debug("Received charge request: sessionId={} subscriberId={}",
                request.sessionId(), request.subscriberId());

        return gatewayService.charge(request)
                .map(resp -> switch (resp.status()) {
                    case "SUCCESS" -> ResponseEntity.ok(resp);
                    case "DENIED"  -> ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(resp);
                    case "TIMEOUT" -> ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(resp);
                    default        -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(resp);
                });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/v1/health/diameter
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping(value = "/health/diameter", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Diameter client health check",
               description = "Returns the current state of the Diameter TCP connection.")
    public Mono<ResponseEntity<java.util.Map<String, Object>>> diameterHealth(
            com.telecom.gateway.diameter.DiameterClient diameterClient) {
        var body = java.util.Map.<String, Object>of(
            "status",  diameterClient.isReady() ? "UP" : "DOWN",
            "pending", diameterClient.getPendingRequestCount()
        );
        HttpStatus status = diameterClient.isReady()
                ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return Mono.just(ResponseEntity.status(status).body(body));
    }
}
