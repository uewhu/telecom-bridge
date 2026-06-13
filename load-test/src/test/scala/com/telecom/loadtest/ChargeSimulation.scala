package com.telecom.loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import java.util.UUID

/**
 * Gatling simulation for the Telecom-Bridge REST-to-Diameter Gateway.
 *
 * Target:
 *   - 100 TPS constant injection rate
 *   - 500,000 total transactions
 *   - p95 response time < 100ms
 *
 * Run with:
 *   mvn gatling:test -pl load-test
 *
 * Or directly:
 *   JAVA_OPTS="-DGATLING_BASE_URL=http://localhost:8080" mvn gatling:test -pl load-test
 */
class ChargeSimulation extends Simulation {

  // ── Configuration ──────────────────────────────────────────────────────────
  val baseUrl       = sys.env.getOrElse("GATLING_BASE_URL", "http://localhost:8080")
  val targetTps     = sys.env.getOrElse("TARGET_TPS",       "100").toInt
  val totalRequests = sys.env.getOrElse("TOTAL_REQUESTS",   "500000").toInt
  val rampDuration  = 10.seconds

  // Duration to sustain 100 TPS to reach 500K transactions
  // (ignoring ramp: 500000 / 100 = 5000 seconds ≈ 83 min)
  val sustainDuration = (totalRequests / targetTps).seconds

  // ── HTTP Protocol ──────────────────────────────────────────────────────────
  val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .shareConnections

  // ── Request body feeder ───────────────────────────────────────────────────
  val serviceFeeder = Iterator.continually(Map(
    "sessionId"      -> s"sess-${UUID.randomUUID()}",
    "subscriberId"   -> s"4477009${(10000 + scala.util.Random.nextInt(90000)).toString}",
    "serviceId"      -> scala.util.Random.shuffle(List("DATA", "VOICE", "SMS")).head,
    "requestedUnits" -> (1024L * (1 + scala.util.Random.nextInt(1024)))
  ))

  // ── Scenario ───────────────────────────────────────────────────────────────
  val chargeScenario = scenario("Online Charge")
    .feed(serviceFeeder)
    .exec(
      http("POST /api/v1/charge")
        .post("/api/v1/charge")
        .body(StringBody(session => {
          val sessionId      = session("sessionId").as[String]
          val subscriberId   = session("subscriberId").as[String]
          val serviceId      = session("serviceId").as[String]
          val requestedUnits = session("requestedUnits").as[Long]
          s"""{
             |  "sessionId": "$sessionId",
             |  "subscriberId": "$subscriberId",
             |  "serviceId": "$serviceId",
             |  "requestedUnits": $requestedUnits
             |}""".stripMargin
        })).asJson
        .check(status.in(200, 402))
        .check(jsonPath("$.status").saveAs("chargeStatus"))
    )
    .exec { session =>
      val status = session("chargeStatus").asOption[String].getOrElse("UNKNOWN")
      // Custom logging for non-success responses
      if (status != "SUCCESS") {
        println(s"[WARN] Non-success charge status: $status for session ${session("sessionId").as[String]}")
      }
      session
    }

  // ── Injection Profile ──────────────────────────────────────────────────────
  setUp(
    chargeScenario.inject(
      rampUsersPerSec(1).to(targetTps).during(rampDuration),       // Warm-up
      constantUsersPerSec(targetTps).during(sustainDuration)       // Sustained 100 TPS
    )
  )
  .protocols(httpProtocol)
  .assertions(
    // SLA: p95 must be under 100ms
    global.responseTime.percentile(95).lt(100),
    // SLA: success rate > 99%
    global.successfulRequests.percent.gte(99.0),
    // SLA: mean response time < 80ms
    global.responseTime.mean.lt(80)
  )
}
