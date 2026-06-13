package com.telecom.gateway.diameter;

import com.telecom.gateway.config.DiameterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Sends periodic Device-Watchdog-Requests (DWR) to keep the Diameter connection alive
 * per RFC 6733 §5.5.
 *
 * <p>Watchdog state machine (simplified):</p>
 * <pre>
 *   INITIAL → send DWR → OKAY (DWA received)
 *   OKAY    → send DWR → OKAY | SUSPECT (no DWA)
 *   SUSPECT → send DWR → OKAY | DOWN (no DWA again)
 * </pre>
 */
@Component
public class WatchdogScheduler {

    private static final Logger log = LoggerFactory.getLogger(WatchdogScheduler.class);

    private static final long DWR_TIMEOUT_MS = 5_000L;

    private final DiameterClient diameterClient;

    private enum WatchdogState { INITIAL, OKAY, SUSPECT, DOWN }
    private volatile WatchdogState watchdogState = WatchdogState.INITIAL;
    private int missedResponses = 0;

    public WatchdogScheduler(DiameterClient diameterClient, DiameterProperties props) {
        this.diameterClient = diameterClient;
    }

    /**
     * Scheduled at fixed rate from application.yml (default: 30s).
     * Sends DWR and updates watchdog state based on DWA response.
     */
    @Scheduled(fixedDelayString = "${diameter.client.watchdog-interval-ms:30000}")
    public void sendWatchdog() {
        if (!diameterClient.isReady()) {
            log.debug("Skipping DWR — Diameter client not ready.");
            watchdogState = WatchdogState.INITIAL;
            return;
        }

        log.debug("Sending DWR (state={})", watchdogState);
        diameterClient.sendDWR()
            .orTimeout(DWR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .whenComplete((dwa, ex) -> {
                if (ex != null) {
                    missedResponses++;
                    if (missedResponses == 1) {
                        watchdogState = WatchdogState.SUSPECT;
                        log.warn("DWA not received — connection SUSPECT (missed={})", missedResponses);
                    } else {
                        watchdogState = WatchdogState.DOWN;
                        log.error("DWA repeatedly not received — connection DOWN (missed={}). " +
                                  "Connection will be recycled.", missedResponses);
                    }
                } else {
                    if (watchdogState != WatchdogState.OKAY) {
                        log.info("Watchdog recovered: DWA received. State → OKAY");
                    }
                    watchdogState  = WatchdogState.OKAY;
                    missedResponses = 0;
                    log.debug("DWA received. Watchdog OK. Pending requests: {}",
                              diameterClient.getPendingRequestCount());
                }
            });
    }
}
