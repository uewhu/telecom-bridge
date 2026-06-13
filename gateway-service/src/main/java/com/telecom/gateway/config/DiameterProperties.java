package com.telecom.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Strongly-typed configuration properties for the Diameter client.
 * Bound from {@code diameter.*} in {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "diameter")
@Validated
public class DiameterProperties {

    @NotBlank
    private String serverHost = "localhost";

    @Positive
    private int serverPort = 3868;

    @NotBlank
    private String originHost = "gw.telecom.com";

    @NotBlank
    private String originRealm = "telecom.com";

    @NotBlank
    private String destinationRealm = "telecom.com";

    @Positive
    private long timeoutMs = 5000;

    @Positive
    private long watchdogIntervalMs = 30000;

    // ──────────────────────────────────────────────────────────────────────────
    // Getters / Setters
    // ──────────────────────────────────────────────────────────────────────────

    public String getServerHost()          { return serverHost;         }
    public void   setServerHost(String v)  { this.serverHost = v;       }

    public int    getServerPort()          { return serverPort;         }
    public void   setServerPort(int v)     { this.serverPort = v;       }

    public String getOriginHost()          { return originHost;         }
    public void   setOriginHost(String v)  { this.originHost = v;       }

    public String getOriginRealm()         { return originRealm;        }
    public void   setOriginRealm(String v) { this.originRealm = v;      }

    public String getDestinationRealm()          { return destinationRealm;         }
    public void   setDestinationRealm(String v)  { this.destinationRealm = v;       }

    public long   getTimeoutMs()           { return timeoutMs;          }
    public void   setTimeoutMs(long v)     { this.timeoutMs = v;        }

    public long   getWatchdogIntervalMs()           { return watchdogIntervalMs;  }
    public void   setWatchdogIntervalMs(long v)     { this.watchdogIntervalMs = v;}
}
