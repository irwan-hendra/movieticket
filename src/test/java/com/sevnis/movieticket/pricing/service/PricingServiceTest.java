package com.sevnis.movieticket.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sevnis.movieticket.pricing.dto.Customer;
import com.sevnis.movieticket.pricing.dto.CustomerTicket;
import com.sevnis.movieticket.pricing.dto.PricingQuery;
import com.sevnis.movieticket.pricing.dto.PricingResult;
import io.gorules.zen_engine.JsonBuffer;
import io.gorules.zen_engine.ZenEngine;
import io.gorules.zen_engine.ZenEngineResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/*
 * Pricing rules under test:
 *   Adult    — age 18-64  → $25.00
 *   Senior   — age 65+    → $17.50  (30% off adult, multiplier 0.70)
 *   Teen     — age 11-17  → $12.00
 *   Children — age 0-10   → $5.00   ($3.75 if 3+ children — 25% discount, multiplier 0.75)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PricingService — ZenEngine delegation")
public class PricingServiceTest {

  // ---------------------------------------------------------------------------
  // Price constants — derived from business requirements
  // ---------------------------------------------------------------------------

  private static final BigDecimal ADULT_PRICE = BigDecimal.valueOf(25.00);
  private static final BigDecimal SENIOR_PRICE = BigDecimal.valueOf(17.50); // 25.00 × 0.70
  private static final BigDecimal TEEN_PRICE = BigDecimal.valueOf(12.00);
  private static final BigDecimal CHILDREN_PRICE = BigDecimal.valueOf(5.00);
  private static final BigDecimal CHILDREN_BULK_PRICE = BigDecimal.valueOf(3.75);  // 5.00 × 0.75

  private static final double SENIOR_MULTIPLIER = 0.70;
  private static final double CHILDREN_MULTIPLIER = 0.75;

  private static final String DECISION_RULE = "pricing.json";

  @Mock
  private ZenEngine zenEngine;
  @Spy
  private ObjectMapper objectMapper = new ObjectMapper();
  @InjectMocks
  private PricingService pricingService;

  private PricingQuery sampleQuery;
  private PricingResult sampleResult;

  @BeforeEach
  void setUp() {
    // Alice (35) Adult, Eve (15) Teen, Bob (10) Children (alone — no bulk discount), Carol (68) Senior
    sampleQuery = new PricingQuery(List.of(
        new Customer("Alice", 35),
        new Customer("Eve", 15),
        new Customer("Bob", 10),
        new Customer("Carol", 68)
    ));

    sampleResult = new PricingResult(
        1,                   // childrenCount — Bob only, below bulk threshold
        CHILDREN_MULTIPLIER, // 0.75
        SENIOR_MULTIPLIER,   // 0.70
        List.of(
            new CustomerTicket("Alice", 35, "Adult", ADULT_PRICE, ADULT_PRICE),
            new CustomerTicket("Eve", 15, "Teen", TEEN_PRICE, TEEN_PRICE),
            new CustomerTicket("Bob", 10, "Children", CHILDREN_PRICE, CHILDREN_PRICE),
            new CustomerTicket("Carol", 68, "Senior", ADULT_PRICE, SENIOR_PRICE)
        )
    );
  }

  // ---------------------------------------------------------------------------
  // Success scenarios
  // ---------------------------------------------------------------------------

  private void stubEngineWithResult(String rule, PricingResult result) throws Exception {
    byte[] resultJson = objectMapper.writeValueAsBytes(result);
    JsonBuffer buffer = mock(JsonBuffer.class);
    when(buffer.value()).thenReturn(resultJson);

    ZenEngineResponse response = mock(ZenEngineResponse.class);
    when(response.result()).thenReturn(buffer);

    stubEngineWithFuture(rule, CompletableFuture.completedFuture(response));
  }

  // ---------------------------------------------------------------------------
  // Null / missing result
  // ---------------------------------------------------------------------------

  private void stubEngineWithFuture(String rule, CompletableFuture<ZenEngineResponse> future) {
    when(zenEngine.evaluate(eq(rule), any(JsonBuffer.class), isNull())).thenReturn(future);
  }

  // ---------------------------------------------------------------------------
  // Engine / async errors
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("evaluate — success scenarios")
  class SuccessScenarios {

    @Test
    @DisplayName("returns fully populated PricingResult on successful evaluation")
    void evaluate_returnsResult_onSuccessfulEvaluation() throws Exception {
      stubEngineWithResult(DECISION_RULE, sampleResult);

      PricingResult actual = pricingService.evaluate(DECISION_RULE, sampleQuery);

      assertThat(actual.childrenCount()).isEqualTo(1);
      assertThat(actual.childrenDiscountMultiplier()).isEqualTo(CHILDREN_MULTIPLIER);
      assertThat(actual.seniorDiscountMultiplier()).isEqualTo(SENIOR_MULTIPLIER);
      assertThat(actual.customerTickets()).hasSize(4);
    }

    @Test
    @DisplayName("maps each CustomerTicket field correctly for all four ticket types")
    void evaluate_mapsCustomerTickets_correctly() throws Exception {
      stubEngineWithResult(DECISION_RULE, sampleResult);

      PricingResult actual = pricingService.evaluate(DECISION_RULE, sampleQuery);

      CustomerTicket alice = actual.customerTickets().get(0);
      assertThat(alice.name()).isEqualTo("Alice");
      assertThat(alice.age()).isEqualTo(35);
      assertThat(alice.ticketType()).isEqualTo("Adult");
      assertThat(alice.basePrice()).isEqualByComparingTo(ADULT_PRICE);
      assertThat(alice.finalPrice()).isEqualByComparingTo(ADULT_PRICE);

      CustomerTicket eve = actual.customerTickets().get(1);
      assertThat(eve.name()).isEqualTo("Eve");
      assertThat(eve.age()).isEqualTo(15);
      assertThat(eve.ticketType()).isEqualTo("Teen");
      assertThat(eve.basePrice()).isEqualByComparingTo(TEEN_PRICE);
      assertThat(eve.finalPrice()).isEqualByComparingTo(TEEN_PRICE);

      CustomerTicket bob = actual.customerTickets().get(2);
      assertThat(bob.name()).isEqualTo("Bob");
      assertThat(bob.age()).isEqualTo(10);
      assertThat(bob.ticketType()).isEqualTo("Children");
      assertThat(bob.basePrice()).isEqualByComparingTo(CHILDREN_PRICE);
      assertThat(bob.finalPrice()).isEqualByComparingTo(CHILDREN_PRICE); // no bulk discount (<3)

      CustomerTicket carol = actual.customerTickets().get(3);
      assertThat(carol.name()).isEqualTo("Carol");
      assertThat(carol.age()).isEqualTo(68);
      assertThat(carol.ticketType()).isEqualTo("Senior");
      assertThat(carol.basePrice()).isEqualByComparingTo(ADULT_PRICE);
      assertThat(carol.finalPrice()).isEqualByComparingTo(SENIOR_PRICE);
    }

    @Test
    @DisplayName("Children bulk discount: 3 children — finalPrice is $3.75 each (25% off $5.00)")
    void evaluate_threeChildren_bulkDiscountApplied() throws Exception {
      PricingQuery bulkQuery = new PricingQuery(List.of(
          new Customer("Bob1", 8),
          new Customer("Bob2", 7),
          new Customer("Bob3", 5)
      ));
      PricingResult bulkResult = new PricingResult(
          3, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER,
          List.of(
              new CustomerTicket("Bob1", 8, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
              new CustomerTicket("Bob2", 7, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
              new CustomerTicket("Bob3", 5, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE)
          )
      );
      stubEngineWithResult(DECISION_RULE, bulkResult);

      PricingResult actual = pricingService.evaluate(DECISION_RULE, bulkQuery);

      assertThat(actual.childrenCount()).isEqualTo(3);
      actual.customerTickets().forEach(ticket -> {
        assertThat(ticket.ticketType()).isEqualTo("Children");
        assertThat(ticket.finalPrice()).isEqualByComparingTo(CHILDREN_BULK_PRICE);
      });
    }

    @Test
    @DisplayName("single Adult customer: one ticket returned, childrenCount is zero")
    void evaluate_singleAdult_returnsOneTicket() throws Exception {
      PricingQuery singleQuery = new PricingQuery(List.of(new Customer("Dave", 25)));
      PricingResult singleResult = new PricingResult(
          0, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER,
          List.of(new CustomerTicket("Dave", 25, "Adult", ADULT_PRICE, ADULT_PRICE))
      );
      stubEngineWithResult(DECISION_RULE, singleResult);

      PricingResult actual = pricingService.evaluate(DECISION_RULE, singleQuery);

      assertThat(actual.customerTickets()).hasSize(1);
      assertThat(actual.customerTickets().get(0).ticketType()).isEqualTo("Adult");
      assertThat(actual.customerTickets().get(0).finalPrice()).isEqualByComparingTo(ADULT_PRICE);
      assertThat(actual.childrenCount()).isZero();
    }

    @Test
    @DisplayName("passes the exact decision rule name to ZenEngine")
    void evaluate_forwardsDecisionRuleName() throws Exception {
      String customRule = "vip-pricing.json";
      stubEngineWithResult(customRule, sampleResult);

      pricingService.evaluate(customRule, sampleQuery);

      verify(zenEngine).evaluate(eq(customRule), any(JsonBuffer.class), isNull());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("evaluate — null / missing result")
  class NullResultScenarios {

    @Test
    @DisplayName("throws RuntimeException when ZenEngineResponse itself is null")
    void evaluate_throwsRuntimeException_whenResponseIsNull() {
      stubEngineWithFuture(DECISION_RULE, CompletableFuture.completedFuture(null));

      assertThatThrownBy(() -> pricingService.evaluate(DECISION_RULE, sampleQuery))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Rule evaluation failed for: " + DECISION_RULE)
          .hasCauseInstanceOf(Exception.class)
          .getCause().hasMessageContaining("Missing Result");
    }

    @Test
    @DisplayName("throws RuntimeException when ZenEngineResponse.result() is null")
    void evaluate_throwsRuntimeException_whenResultBufferIsNull() {
      ZenEngineResponse response = mock(ZenEngineResponse.class);
      when(response.result()).thenReturn(null);
      stubEngineWithFuture(DECISION_RULE, CompletableFuture.completedFuture(response));

      assertThatThrownBy(() -> pricingService.evaluate(DECISION_RULE, sampleQuery))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Rule evaluation failed for: " + DECISION_RULE)
          .getCause().hasMessageContaining("Missing Result");
    }
  }

  @Nested
  @DisplayName("evaluate — engine / async errors")
  class EngineErrorScenarios {

    @Test
    @DisplayName("wraps ExecutionException from the engine future in RuntimeException")
    void evaluate_wrapsExecutionException() {
      CompletableFuture<ZenEngineResponse> failedFuture = new CompletableFuture<>();
      failedFuture.completeExceptionally(new RuntimeException("Engine internal error"));
      stubEngineWithFuture(DECISION_RULE, failedFuture);

      assertThatThrownBy(() -> pricingService.evaluate(DECISION_RULE, sampleQuery))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Rule evaluation failed for: " + DECISION_RULE);
    }

    @Test
    @DisplayName("wraps TimeoutException when engine exceeds 10-second deadline")
    void evaluate_wrapsTimeoutException() throws Exception {
      @SuppressWarnings("unchecked")
      CompletableFuture<ZenEngineResponse> slowFuture = mock(CompletableFuture.class);
      when(slowFuture.get(10, TimeUnit.SECONDS))
          .thenThrow(new TimeoutException("Deadline exceeded"));
      when(zenEngine.evaluate(eq(DECISION_RULE), any(JsonBuffer.class), isNull()))
          .thenReturn(slowFuture);

      assertThatThrownBy(() -> pricingService.evaluate(DECISION_RULE, sampleQuery))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Rule evaluation failed for: " + DECISION_RULE)
          .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("wraps InterruptedException when thread is interrupted during future.get()")
    void evaluate_wrapsInterruptedException() throws Exception {
      @SuppressWarnings("unchecked")
      CompletableFuture<ZenEngineResponse> interruptedFuture = mock(CompletableFuture.class);
      when(interruptedFuture.get(10, TimeUnit.SECONDS))
          .thenThrow(new InterruptedException("Interrupted"));
      when(zenEngine.evaluate(eq(DECISION_RULE), any(JsonBuffer.class), isNull()))
          .thenReturn(interruptedFuture);

      assertThatThrownBy(() -> pricingService.evaluate(DECISION_RULE, sampleQuery))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Rule evaluation failed for: " + DECISION_RULE);
    }
  }
}