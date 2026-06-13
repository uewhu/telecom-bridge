# 🌐 Telecom-Bridge: REST-to-Diameter Gateway

> **Hackathon Submission** — Cloud Native Java Lead Developer Challenge  
> A high-performance, non-blocking REST-to-Diameter (Ro/Gy) gateway microservice built with Spring Boot 3 + Netty.

---

## 📐 Architecture

```
REST Client (curl / JMeter / Gatling)
          │
          ▼  POST /api/v1/charge  (Spring WebFlux — non-blocking)
┌─────────────────────────────────────────────┐
│         Gateway Service (port 8080)          │
│                                              │
│  ChargeController (WebFlux)                 │
│      │                                       │
│  DiameterGatewayService                     │
│      │  builds CCR, wraps in Mono           │
│  DiameterClient  (Netty NIO)                │
│      │  ConcurrentHashMap<HbH, CF>          │
│      │  AtomicLong Hop-by-Hop counter       │
└─────────────────────────────────────────────┘
          │  Diameter over TCP :3868
          ▼
┌─────────────────────────────────────────────┐
│      Diameter Simulator (port 3868)          │
│  Netty ServerBootstrap                      │
│  DiameterServerHandler                      │
│  CER/CEA → DWR/DWA → CCR/CCA (50-100ms)   │
└─────────────────────────────────────────────┘
```

---

## 📁 Project Structure

```
telecom-bridge/
├── pom.xml                           ← Parent multi-module Maven POM (Java 21)
├── gateway-service/                  ← REST microservice + Diameter client
│   ├── pom.xml
│   └── src/main/java/com/telecom/gateway/
│       ├── GatewayApplication.java
│       ├── controller/
│       │   ├── ChargeController.java       ← POST /api/v1/charge
│       │   └── GlobalExceptionHandler.java
│       ├── dto/
│       │   ├── ChargeRequest.java          ← Input record
│       │   ├── ChargeResponse.java         ← Output record
│       │   └── ErrorResponse.java
│       ├── service/
│       │   └── DiameterGatewayService.java ← CCR builder + CCA translator
│       ├── diameter/
│       │   ├── DiameterClient.java         ← Netty async client (core)
│       │   ├── DiameterClientHandler.java  ← Netty ChannelHandler
│       │   ├── DiameterCodec.java          ← RFC 6733 encoder/decoder
│       │   ├── DiameterMessage.java        ← Message POJO (Builder pattern)
│       │   ├── DiameterAVP.java            ← AVP model with padding
│       │   ├── DiameterConstants.java      ← All protocol constants
│       │   ├── DiameterException.java
│       │   └── WatchdogScheduler.java      ← DWR/DWA keepalive
│       └── config/
│           ├── DiameterProperties.java
│           └── AsyncConfig.java
├── diameter-simulator/               ← Standalone Diameter server
│   └── src/main/java/com/telecom/simulator/
│       ├── SimulatorApplication.java
│       ├── DiameterServer.java             ← Netty ServerBootstrap
│       └── DiameterServerHandler.java      ← CER/DWR/CCR handler
├── load-test/                        ← Gatling simulation
│   └── src/test/scala/com/telecom/loadtest/
│       └── ChargeSimulation.scala          ← 100 TPS × 500K transactions
├── docker/
│   ├── Dockerfile.gateway
│   ├── Dockerfile.simulator
│   ├── docker-compose.yml            ← Full stack + Prometheus + Grafana
│   └── prometheus.yml
└── README.md
```

---

## 🚀 Quick Start

### Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+     |
| Maven | 3.9+   |
| Docker | 24+   |
| Docker Compose | 2.x |

---

### Option A — Docker Compose (Recommended)

```bash
# From the project root
cd docker
docker compose up --build
```

Services started:

| Service | URL |
|---------|-----|
| Gateway REST API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Actuator / Health | http://localhost:8080/actuator/health |
| Prometheus | http://localhost:9091 |
| Grafana | http://localhost:3000 (admin/telecom123) |
| Diameter Simulator | tcp://localhost:3868 |

---

### Option B — Run Locally (Two Terminals)

**Terminal 1 — Start the Diameter Simulator:**

```bash
cd telecom-bridge
mvn package -pl diameter-simulator -am -DskipTests -q
java --enable-preview -jar diameter-simulator/target/diameter-simulator-*.jar
```

**Terminal 2 — Start the Gateway Service:**

```bash
java --enable-preview -jar gateway-service/target/gateway-service-*.jar
```

---

## 📡 API Reference

### `POST /api/v1/charge`

**Request:**
```json
{
  "sessionId":      "sess-abc-001",
  "subscriberId":   "447700900001",
  "serviceId":      "DATA",
  "requestedUnits": 1048576
}
```

**Response (200 — Success):**
```json
{
  "sessionId":    "sess-abc-001",
  "resultCode":   2001,
  "grantedUnits": 10485760,
  "status":       "SUCCESS"
}
```

**Response (504 — Timeout):**
```json
{
  "sessionId": "sess-abc-001",
  "resultCode": -1,
  "grantedUnits": 0,
  "status": "TIMEOUT",
  "message": "Diameter server did not respond within the configured timeout."
}
```

**HTTP Status Mapping:**

| Status | Scenario |
|--------|----------|
| `200 OK` | Charge granted (Result-Code: 2001) |
| `402 Payment Required` | Charge denied by OCS |
| `400 Bad Request` | Invalid/missing request fields |
| `503 Service Unavailable` | Diameter client not connected |
| `504 Gateway Timeout` | No CCA received within 5s |

---

**Test with curl:**
```bash
curl -s -X POST http://localhost:8080/api/v1/charge \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "sess-001",
    "subscriberId": "447700900001",
    "serviceId": "DATA",
    "requestedUnits": 1048576
  }' | jq .
```

---

## 🔬 Diameter Protocol Implementation

### Message Header (RFC 6733 §3)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    Version    |                 Message Length                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| command flags |                  Command-Code                  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Application-ID                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Hop-by-Hop Identifier                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      End-to-End Identifier                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Implemented Command Codes

| Command | Code | Direction |
|---------|------|-----------|
| CER / CEA | 257 | Client → Server / Server → Client |
| DWR / DWA | 280 | Client → Server / Server → Client |
| CCR / CCA | 272 | Client → Server / Server → Client |

### Implemented AVPs (RFC 6733 + RFC 4006)

| AVP | Code | Type |
|-----|------|------|
| Session-Id | 263 | UTF8String |
| Result-Code | 268 | Unsigned32 |
| Origin-Host | 264 | DiameterIdentity |
| Origin-Realm | 296 | DiameterIdentity |
| Dest-Realm | 283 | DiameterIdentity |
| Auth-Application-Id | 258 | Unsigned32 |
| CC-Request-Type | 416 | Enumerated |
| CC-Request-Number | 415 | Unsigned32 |
| Subscription-Id | 443 | Grouped |
| Subscription-Id-Type | 450 | Enumerated |
| Subscription-Id-Data | 444 | UTF8String |
| Requested-Service-Unit | 437 | Grouped |
| Granted-Service-Unit | 431 | Grouped |
| CC-Total-Octets | 421 | Unsigned64 |
| Service-Identifier | 439 | Unsigned32 |

### Async Architecture — Hop-by-Hop Matching

```java
// On CCR send:
ConcurrentHashMap<Long, CompletableFuture<DiameterMessage>> pendingRequests;
pendingRequests.put(hopByHopId, responseFuture);

// On CCA receive (Netty I/O thread):
CompletableFuture<DiameterMessage> pending = pendingRequests.remove(cca.getHopByHopId());
pending.complete(cca);  // unblocks the reactive chain
```

---

## 🧪 Running Unit Tests

```bash
# Run all unit tests
mvn test -pl gateway-service

# Run only AVP encoding tests
mvn test -pl gateway-service -Dtest=DiameterAVPTest

# Run only codec round-trip tests
mvn test -pl gateway-service -Dtest=DiameterCodecTest
```

Tests cover:
- AVP 4-byte boundary padding
- Unsigned32 / Unsigned64 encode/decode round-trips
- UTF8String encoding with unicode
- Grouped AVP (Subscription-Id) nested encoding
- Codec encode → decode complete message round-trip
- Partial message handling (buffer reset)

---

## 📊 Load Testing

### Run Gatling (100 TPS, 500K transactions)

```bash
# Quick smoke test (1000 requests at 10 TPS)
TOTAL_REQUESTS=1000 TARGET_TPS=10 mvn gatling:test -pl load-test

# Full load test (500K at 100 TPS — takes ~83 minutes)
mvn gatling:test -pl load-test

# Against Docker deployment
GATLING_BASE_URL=http://localhost:8080 mvn gatling:test -pl load-test
```

### SLA Assertions (built-in to simulation)

| Metric | Target |
|--------|--------|
| p95 response time | < 100ms |
| Mean response time | < 80ms |
| Success rate | ≥ 99% |

### Capture PCAP Trace

```bash
# Linux/macOS:
sudo tcpdump -i lo -w transaction_flow.pcap port 3868 or port 8080

# Windows (Wireshark CLI):
"C:\Program Files\Wireshark\tshark.exe" -i Loopback -w transaction_flow.pcap -f "port 3868"
```

Open `transaction_flow.pcap` in Wireshark → Filter: `diameter`

---

## 📈 Monitoring

After starting with Docker Compose:

1. **Grafana** at http://localhost:3000 — add Prometheus datasource (`http://prometheus:9090`)
2. **Key metrics exposed:**
   - `diameter_ccr_latency_seconds` — histogram of CCR→CCA round-trip time
   - `diameter_ccr_success_total` — successful charge counter
   - `diameter_ccr_denied_total` — denied charges by result code
   - `diameter_ccr_timeout_total` — timed-out CCR requests
   - `diameter_ccr_error_total` — error counter by type

---

## ⚙️ Configuration

All settings are configurable via environment variables:

| Property | Env Variable | Default |
|----------|-------------|---------|
| Diameter server host | `DIAMETER_SERVER_HOST` | `localhost` |
| Diameter server port | `DIAMETER_SERVER_PORT` | `3868` |
| Origin-Host AVP | `DIAMETER_ORIGIN_HOST` | `gw.telecom.com` |
| Origin-Realm AVP | `DIAMETER_ORIGIN_REALM` | `telecom.com` |
| Response timeout | — | `5000ms` |
| DWR interval | — | `30000ms` |

---

## 🔒 Error Handling

| Failure Scenario | Behavior |
|-----------------|----------|
| Diameter server DOWN on startup | Auto-retry with exponential backoff (up to 10 attempts) |
| Connection lost during operation | All pending requests fail immediately; auto-reconnect triggered |
| No CCA within 5s | `504 Gateway Timeout` returned to REST caller |
| CCA Result-Code ≠ 2001 | `402 Payment Required` returned |
| Invalid JSON body | `400 Bad Request` with field-level validation messages |
| Diameter Result-Code 3004 (Too Busy) | Translated to `503 Service Unavailable` |

---

## 🏗️ Build for Production

```bash
# Build all modules
mvn clean package -DskipTests

# Build and run Docker images
docker build -f docker/Dockerfile.simulator -t telecom-bridge/simulator:1.0.0 .
docker build -f docker/Dockerfile.gateway   -t telecom-bridge/gateway:1.0.0   .
```

---

## 🎯 Evaluation Criteria — Addressed

| Criterion | Implementation |
|-----------|---------------|
| **Concurrency Management** | `ConcurrentHashMap<Long, CompletableFuture>` keyed by Hop-by-Hop ID; `AtomicLong` counter |
| **Protocol Accuracy** | RFC 6733 / RFC 4006 compliant; proper 4-byte AVP padding; correct command codes & App-ID |
| **Resource Management** | Netty event loop groups shut down on `@PreDestroy`; pending futures failed on disconnect |
| **Error Handling** | 504 on timeout, 503 on unreachable, 402 on denied, 400 on invalid input |
| **Code Quality** | Builder pattern, records, SLF4J structured logging, JUnit 5 + AssertJ unit tests |
| **Dockerization** | Multi-stage Dockerfiles; Docker Compose with Prometheus + Grafana |
| **Load Testing** | Gatling simulation with p95 < 100ms SLA assertions |

---

## 📜 License

Apache 2.0 — see [LICENSE](LICENSE)
