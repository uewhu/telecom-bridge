package com.telecom.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Standalone Diameter server simulator.
 *
 * <p>Listens on TCP port 3868 and simulates a Diameter Ro/Gy server by:
 * <ul>
 *   <li>Handling CER/CEA capabilities exchange.</li>
 *   <li>Responding to DWR with DWA.</li>
 *   <li>Processing CCR and returning CCA after a 50-100ms simulated delay.</li>
 * </ul>
 */
@SpringBootApplication
public class SimulatorApplication {

    private static final Logger log = LoggerFactory.getLogger(SimulatorApplication.class);

    private final DiameterServer diameterServer;

    public SimulatorApplication(DiameterServer diameterServer) {
        this.diameterServer = diameterServer;
    }

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║   Telecom-Bridge Diameter Simulator — READY on :3868 ║");
        log.info("╚══════════════════════════════════════════════════════╝");
    }
}
