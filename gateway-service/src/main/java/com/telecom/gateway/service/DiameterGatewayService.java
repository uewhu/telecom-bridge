package com.telecom.gateway.service;

import com.telecom.gateway.config.DiameterProperties;
import com.telecom.gateway.diameter.*;
import com.telecom.gateway.dto.ChargeRequest;
import com.telecom.gateway.dto.ChargeResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the REST-to-Diameter translation pipeline.
 *
 * <ol>
 *   <li>Converts {@link ChargeRequest} → Diameter CCR message.</li>
 *   <li>Dispatches CCR via {@link DiameterClient}.</li>
 *   <li>Wraps the {@link CompletableFuture} in a reactive {@link Mono}.</li>
 *   <li>Translates the CCA response → {@link ChargeResponse}.</li>
 * </ol>
 *
 * <p>The reactive chain is fully non-blocking: the HTTP request thread is released
 * immediately after the CCR is dispatched over the Netty channel.</p>
 */
@Service
public class DiameterGatewayService {

    private static final Logger log = LoggerFactory.getLogger(DiameterGatewayService.class);

    private final DiameterClient     diameterClient;
    private final DiameterProperties props;
    private final MeterRegistry      meterRegistry;

    private final AtomicInteger      ccRequestNumber = new AtomicInteger(0);

    public DiameterGatewayService(DiameterClient diameterClient,
                                   DiameterProperties props,
                                   MeterRegistry meterRegistry) {
        this.diameterClient = diameterClient;
        this.props          = props;
        this.meterRegistry  = meterRegistry;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Processes a charge request asynchronously.
     *
     * @param request the validated charge request
     * @return a {@link Mono} that completes with the charge response
     */
    public Mono<ChargeResponse> charge(ChargeRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);

        // Build the CCR
        DiameterMessage ccr = buildCCR(request);
        long hbhId          = ccr.getHopByHopId();

        log.info("Dispatching CCR: sessionId={} subscriberId={} service={} units={} hbh={}",
                request.sessionId(), request.subscriberId(),
                request.serviceId(), request.requestedUnits(), hbhId);

        // Send CCR, wrap CompletableFuture in Mono, apply timeout
        CompletableFuture<DiameterMessage> ccrFuture = diameterClient.sendCCR(ccr);

        return Mono.fromCompletionStage(ccrFuture)
                .timeout(java.time.Duration.ofMillis(props.getTimeoutMs()))
                .map(cca -> {
                    ChargeResponse resp = translateCca(request.sessionId(), cca);
                    log.info("CCA received: sessionId={} resultCode={} status={} hbh={}",
                            request.sessionId(), resp.resultCode(), resp.status(), hbhId);
                    return resp;
                })
                .onErrorResume(TimeoutException.class, ex -> {
                    log.warn("CCR timed out after {}ms: sessionId={} hbh={}",
                            props.getTimeoutMs(), request.sessionId(), hbhId);
                    meterRegistry.counter("diameter.ccr.timeout").increment();
                    return Mono.just(ChargeResponse.timeout(request.sessionId()));
                })
                .onErrorResume(DiameterException.class, ex -> {
                    log.error("Diameter error for sessionId={}: {}", request.sessionId(), ex.getMessage());
                    meterRegistry.counter("diameter.ccr.error",
                            "type", "diameter_exception").increment();
                    return Mono.just(ChargeResponse.error(request.sessionId(), ex.getMessage()));
                })
                .onErrorResume(ex -> {
                    log.error("Unexpected error for sessionId={}: {}",
                            request.sessionId(), ex.getMessage(), ex);
                    meterRegistry.counter("diameter.ccr.error",
                            "type", "unexpected").increment();
                    return Mono.just(ChargeResponse.error(request.sessionId(), "Internal processing error"));
                })
                .doFinally(sig -> sample.stop(
                    meterRegistry.timer("diameter.ccr.latency",
                            "status", sig.name())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CCR Builder — RFC 4006 §3.1
    // ──────────────────────────────────────────────────────────────────────────

    private DiameterMessage buildCCR(ChargeRequest request) {
        long hbhId = diameterClient.nextHopByHopId();
        long e2eId = diameterClient.nextEndToEndId();
        int  reqNum = ccRequestNumber.getAndIncrement();

        // Subscription-Id grouped AVP (MSISDN)
        DiameterAVP subscriptionId = DiameterAVP.ofGrouped(
            DiameterConstants.AVP_SUBSCRIPTION_ID,
            DiameterAVP.ofEnumerated(DiameterConstants.AVP_SUBSCRIPTION_ID_TYPE,
                    DiameterConstants.SUBSCRIPTION_ID_TYPE_E164),
            DiameterAVP.ofUtf8String(DiameterConstants.AVP_SUBSCRIPTION_ID_DATA,
                    request.subscriberId())
        );

        // Requested-Service-Unit grouped AVP (CC-Total-Octets for data service)
        DiameterAVP requestedServiceUnit = DiameterAVP.ofGrouped(
            DiameterConstants.AVP_REQUESTED_SERVICE_UNIT,
            DiameterAVP.ofUnsigned64(DiameterConstants.AVP_CC_TOTAL_OCTETS,
                    request.requestedUnits())
        );

        return DiameterMessage.builder()
                .requestFlag()
                .proxiableFlag()
                .commandCode(DiameterConstants.CMD_CREDIT_CONTROL)
                .applicationId(DiameterConstants.APP_ID_CREDIT_CONTROL)
                .hopByHopId(hbhId)
                .endToEndId(e2eId)
                // Mandatory base AVPs
                .addAvp(DiameterAVP.ofUtf8String(DiameterConstants.AVP_SESSION_ID,
                        request.sessionId()))
                .addAvp(DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_HOST,
                        props.getOriginHost()))
                .addAvp(DiameterAVP.ofUtf8String(DiameterConstants.AVP_ORIGIN_REALM,
                        props.getOriginRealm()))
                .addAvp(DiameterAVP.ofUtf8String(DiameterConstants.AVP_DEST_REALM,
                        props.getDestinationRealm()))
                .addAvp(DiameterAVP.ofUnsigned32(DiameterConstants.AVP_AUTH_APPLICATION_ID,
                        DiameterConstants.APP_ID_CREDIT_CONTROL))
                // Credit Control specific AVPs
                .addAvp(DiameterAVP.ofEnumerated(DiameterConstants.AVP_CC_REQUEST_TYPE,
                        DiameterConstants.CC_REQUEST_TYPE_INITIAL))
                .addAvp(DiameterAVP.ofUnsigned32(DiameterConstants.AVP_CC_REQUEST_NUMBER, reqNum))
                .addAvp(DiameterAVP.ofUnsigned32(DiameterConstants.AVP_SERVICE_IDENTIFIER, 1))
                .addAvp(subscriptionId)
                .addAvp(requestedServiceUnit)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CCA Translation
    // ──────────────────────────────────────────────────────────────────────────

    private ChargeResponse translateCca(String sessionId, DiameterMessage cca) {
        long resultCode = cca.getResultCode();

        if (resultCode == DiameterConstants.RESULT_SUCCESS
                || resultCode == DiameterConstants.RESULT_LIMITED_SUCCESS) {

            long grantedUnits = cca.findAvp(DiameterConstants.AVP_GRANTED_SERVICE_UNIT)
                    .map(this::extractGrantedUnits)
                    .orElse(0L);
            meterRegistry.counter("diameter.ccr.success").increment();
            return ChargeResponse.success(sessionId, resultCode, grantedUnits);

        } else {
            meterRegistry.counter("diameter.ccr.denied",
                    "result_code", String.valueOf(resultCode)).increment();
            return ChargeResponse.denied(sessionId, resultCode);
        }
    }

    /**
     * Extracts CC-Total-Octets from a Granted-Service-Unit grouped AVP.
     */
    private long extractGrantedUnits(DiameterAVP gsuAvp) {
        byte[] raw = gsuAvp.getValue();
        if (raw.length < 8) return 0L;
        // Walk the nested grouped AVPs to find CC-Total-Octets (421)
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw);
        while (bb.remaining() >= 8) {
            DiameterAVP child = DiameterAVP.decode(bb);
            if (child.getCode() == DiameterConstants.AVP_CC_TOTAL_OCTETS) {
                return child.getValueAsUnsigned64();
            }
        }
        return 0L;
    }
}
