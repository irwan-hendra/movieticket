package com.sevnis.movieticket.perf;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.listFeeder;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PricingSimulation extends Simulation {

  // ---------------------------------------------------------------------------
  // Configuration — override via system properties:
  //   mvn gatling:test -Dperf.baseUrl=http://staging:8080 -Dperf.targetRps=50
  // ---------------------------------------------------------------------------

  private static final String BASE_URL =
      System.getProperty("perf.baseUrl", "http://localhost:8080");

  private static final int TARGET_RPS =
      Integer.parseInt(System.getProperty("perf.targetRps", "20"));

  private static final int RAMP_SECONDS =
      Integer.parseInt(System.getProperty("perf.rampSeconds", "30"));

  private static final int STEADY_SECONDS =
      Integer.parseInt(System.getProperty("perf.steadySeconds", "60"));

  // SLA thresholds
  private static final int P95_MS = 500;
  private static final int P99_MS = 1000;

  // ---------------------------------------------------------------------------
  // HTTP protocol
  // ---------------------------------------------------------------------------
  private static final AtomicLong TX_SEQ = new AtomicLong(1_000_000);

  // ---------------------------------------------------------------------------
  // Payload feeder — cycles through realistic customer group combinations
  // ---------------------------------------------------------------------------
  HttpProtocolBuilder httpProtocol = http
      .baseUrl(BASE_URL)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json")
      .userAgentHeader("Gatling-PricingPerf/1.0");
  FeederBuilder<Object> payloadFeeder = listFeeder(buildPayloads()).circular();

  // ---------------------------------------------------------------------------
  // Scenario
  // ---------------------------------------------------------------------------

  ScenarioBuilder pricingScenario = scenario("Price movie tickets")
      .feed(payloadFeeder)
      .exec(
          http("POST /api/1.0/pricing")
              .post("/api/1.0/pricing")
              .body(StringBody("#{body}")).asJson()
              .check(status().is(200))
              .check(jsonPath("$.transactionId").exists())
              .check(jsonPath("$.tickets").exists())
              .check(jsonPath("$.tickets[*]").count().gte(1))
              .check(jsonPath("$.totalCost").exists())
              .check(jsonPath("$.totalCost").ofDouble().gt(0.0))
      );

  // ---------------------------------------------------------------------------
  // setUp — must be called exactly once inside this instance initializer block
  // ---------------------------------------------------------------------------

  {
    setUp(
        pricingScenario.injectOpen(
            nothingFor(Duration.ofSeconds(5)),
            rampUsersPerSec(1).to(TARGET_RPS).during(Duration.ofSeconds(RAMP_SECONDS)),
            constantUsersPerSec(TARGET_RPS).during(Duration.ofSeconds(STEADY_SECONDS))
        )
    )
        .protocols(httpProtocol)
        .assertions(
            global().responseTime().percentile(95).lt(P95_MS),
            global().responseTime().percentile(99).lt(P99_MS),
            global().successfulRequests().percent().gt(99.0),
            global().responseTime().mean().lt(P95_MS / 2)
        );
  }

  // ---------------------------------------------------------------------------
  // Payload builder — pre-builds a pool of bodies; feeder cycles through them
  // ---------------------------------------------------------------------------

  private static List<Map<String, Object>> buildPayloads() {
    return List.of(
        body(TX_SEQ.getAndIncrement(), singleCustomer("Alice", 35)),              // ADULT only
        body(TX_SEQ.getAndIncrement(), singleCustomer("Bob", 10)),                // CHILD only
        body(TX_SEQ.getAndIncrement(), singleCustomer("Carol", 68)),              // SENIOR only
        body(TX_SEQ.getAndIncrement(), twoCustomers("Dave", 42, "Eve", 12)),      // ADULT + CHILD
        body(TX_SEQ.getAndIncrement(), twoCustomers("Frank", 70, "Grace", 28)),   // SENIOR + ADULT
        body(TX_SEQ.getAndIncrement(),
            threeCustomers("Hank", 5, "Iris", 33, "Joe", 65)) // all types
    );
  }

  private static Map<String, Object> body(long txId, String customers) {
    return Map.of("body", """
        {"transactionId": %d, "customers": [%s]}
        """.formatted(txId, customers).strip());
  }

  private static String singleCustomer(String name, int age) {
    return customer(name, age);
  }

  private static String twoCustomers(String n1, int a1, String n2, int a2) {
    return customer(n1, a1) + "," + customer(n2, a2);
  }

  private static String threeCustomers(String n1, int a1, String n2, int a2, String n3, int a3) {
    return customer(n1, a1) + "," + customer(n2, a2) + "," + customer(n3, a3);
  }

  private static String customer(String name, int age) {
    return """
        {"name": "%s", "age": %d}
        """.formatted(name, age).strip();
  }
}