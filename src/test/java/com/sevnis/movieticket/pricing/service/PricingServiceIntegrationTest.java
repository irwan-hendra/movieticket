package com.sevnis.movieticket.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sevnis.movieticket.pricing.dto.Customer;
import com.sevnis.movieticket.pricing.dto.CustomerTicket;
import com.sevnis.movieticket.pricing.dto.PricingQuery;
import com.sevnis.movieticket.pricing.dto.PricingResult;
import io.gorules.zen_engine.JsonBuffer;
import io.gorules.zen_engine.ZenEngine;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/*
 * Pricing rules under test:
 *   Adult    — age 18-64  → $25.00
 *   Senior   — age 65+    → $17.50  (30% off adult, multiplier 0.70)
 *   Teen     — age 11-17  → $12.00
 *   Children — age 0-10   → $5.00   ($3.75 if 3+ children — 25% discount, multiplier 0.75)
 */
@SpringBootTest(classes = {PricingService.class, PricingServiceIntegrationTest.TestConfig.class})
@DisplayName("PricingService — ZenEngine rule evaluation")
public class PricingServiceIntegrationTest {

  private static final String DECISION_RULE = "rules\\pricing.json";

  // ---------------------------------------------------------------------------
  // Expected prices — derived from business requirements
  // ---------------------------------------------------------------------------

  private static final BigDecimal ADULT_PRICE = BigDecimal.valueOf(25.00);
  private static final BigDecimal SENIOR_PRICE = BigDecimal.valueOf(17.50); // 25.00 × 0.70
  private static final BigDecimal TEEN_PRICE = BigDecimal.valueOf(12.00);
  private static final BigDecimal CHILDREN_PRICE = BigDecimal.valueOf(5.00);
  private static final BigDecimal CHILDREN_BULK_PRICE = BigDecimal.valueOf(3.75);  // 5.00 × 0.75

  private static final double SENIOR_MULTIPLIER = 0.70;
  private static final double CHILDREN_MULTIPLIER = 0.75;

  @Autowired
  private PricingService pricingService;

  // ---------------------------------------------------------------------------
  // Ticket type classification — one customer per test
  // ---------------------------------------------------------------------------

  @TestConfiguration
  static class TestConfig {

    @Bean
    ZenEngine zenEngine() {
      return new ZenEngine(key -> {
        try (InputStream stream = new ClassPathResource(key).getInputStream()) {
          byte[] bytes = stream.readAllBytes(); // stream closed by try-with-resources
          return CompletableFuture.completedFuture(new JsonBuffer(bytes));
        } catch (Exception e) {
          return CompletableFuture.failedFuture(new RuntimeException(
              "Decision Loader failed for key: " + key + " with message: " + e.getMessage(), e));
        }
      }, null);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  // ---------------------------------------------------------------------------
  // Children bulk discount — 3+ children triggers 25% off
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Ticket type classification")
  class TicketTypeClassification {

    @Test
    @DisplayName("Adult (age 35): classified as 'Adult', finalPrice equals basePrice $25.00")
    void evaluate_adultCustomer_producesAdultTicket() {
      PricingQuery query = new PricingQuery(List.of(new Customer("Alice", 35)));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      CustomerTicket ticket = result.customerTickets().get(0);
      assertThat(ticket.name()).isEqualTo("Alice");
      assertThat(ticket.ticketType()).isEqualTo("Adult");
      assertThat(ticket.basePrice()).isEqualByComparingTo(ADULT_PRICE);
      assertThat(ticket.finalPrice()).isEqualByComparingTo(ADULT_PRICE);
    }

    @Test
    @DisplayName("Teen (age 15): classified as 'Teen', finalPrice equals basePrice $12.00")
    void evaluate_teenCustomer_producesTeenTicket() {
      PricingQuery query = new PricingQuery(List.of(new Customer("Eve", 15)));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      CustomerTicket ticket = result.customerTickets().get(0);
      assertThat(ticket.name()).isEqualTo("Eve");
      assertThat(ticket.ticketType()).isEqualTo("Teen");
      assertThat(ticket.basePrice()).isEqualByComparingTo(TEEN_PRICE);
      assertThat(ticket.finalPrice()).isEqualByComparingTo(TEEN_PRICE);
    }

    @Test
    @DisplayName("Senior (age 68): classified as 'Senior', finalPrice is $17.50 (30% off $25.00)")
    void evaluate_seniorCustomer_producesSeniorTicketWithDiscount() {
      PricingQuery query = new PricingQuery(List.of(new Customer("Carol", 68)));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      CustomerTicket ticket = result.customerTickets().get(0);
      assertThat(ticket.name()).isEqualTo("Carol");
      assertThat(ticket.ticketType()).isEqualTo("Senior");
      assertThat(ticket.basePrice()).isEqualByComparingTo(ADULT_PRICE);
      assertThat(ticket.finalPrice()).isEqualByComparingTo(SENIOR_PRICE);
      assertThat(ticket.finalPrice())
          .isEqualByComparingTo(
              ticket.basePrice().multiply(BigDecimal.valueOf(result.seniorDiscountMultiplier())));
    }

    @Test
    @DisplayName("Children (age 8, alone): classified as 'Children', $5.00 — no bulk discount (only 1 child)")
    void evaluate_singleChild_producesChildrenTicketWithoutDiscount() {
      PricingQuery query = new PricingQuery(List.of(new Customer("Bob", 8)));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      CustomerTicket ticket = result.customerTickets().get(0);
      assertThat(ticket.ticketType()).isEqualTo("Children");
      assertThat(ticket.basePrice()).isEqualByComparingTo(CHILDREN_PRICE);
      assertThat(ticket.finalPrice()).isEqualByComparingTo(CHILDREN_PRICE);
    }
  }

  // ---------------------------------------------------------------------------
  // Age boundary classification — validates rule engine edges directly
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Children bulk discount")
  class ChildrenBulkDiscount {

    @Test
    @DisplayName("2 children: no bulk discount — each $5.00")
    void evaluate_twoChildren_noBulkDiscount() {
      PricingQuery query = new PricingQuery(List.of(
          new Customer("Bob1", 8),
          new Customer("Bob2", 7)
      ));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      assertThat(result.childrenCount()).isEqualTo(2);
      result.customerTickets().forEach(ticket -> {
        assertThat(ticket.ticketType()).isEqualTo("Children");
        assertThat(ticket.finalPrice()).isEqualByComparingTo(CHILDREN_PRICE);
      });
    }

    @Test
    @DisplayName("exactly 3 children: 25% bulk discount applies — each $3.75")
    void evaluate_threeChildren_bulkDiscountApplies() {
      PricingQuery query = new PricingQuery(List.of(
          new Customer("Bob1", 10),
          new Customer("Bob2", 10),
          new Customer("Bob3", 10)
      ));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      assertThat(result.childrenCount()).isEqualTo(3);
      assertThat(result.childrenDiscountMultiplier()).isEqualTo(CHILDREN_MULTIPLIER);
      result.customerTickets().forEach(ticket -> {
        assertThat(ticket.ticketType()).isEqualTo("Children");
        assertThat(ticket.finalPrice()).isEqualByComparingTo(CHILDREN_BULK_PRICE);
        assertThat(ticket.finalPrice())
            .isEqualByComparingTo(
                ticket.basePrice()
                    .multiply(BigDecimal.valueOf(result.childrenDiscountMultiplier())));
      });
    }

    @Test
    @DisplayName("4 children: bulk discount still applies — each $3.75")
    void evaluate_fourChildren_bulkDiscountStillApplies() {
      PricingQuery query = new PricingQuery(List.of(
          new Customer("Bob1", 5),
          new Customer("Bob2", 6),
          new Customer("Bob3", 7),
          new Customer("Bob4", 8)
      ));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      assertThat(result.childrenCount()).isEqualTo(4);
      result.customerTickets().forEach(ticket ->
          assertThat(ticket.finalPrice()).isEqualByComparingTo(CHILDREN_BULK_PRICE)
      );
    }
  }

  // ---------------------------------------------------------------------------
  // Mixed group
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Age boundary classification")
  class AgeBoundaries {

    @Test
    @DisplayName("age 10 (upper bound of Children): classified as 'Children'")
    void evaluate_age10_isChildren() {
      PricingResult result = pricingService.evaluate(DECISION_RULE,
          new PricingQuery(List.of(new Customer("Kid", 10))));
      assertThat(result.customerTickets().get(0).ticketType()).isEqualTo("Children");
    }

    @Test
    @DisplayName("age 11 (lower bound of Teen): classified as 'Teen'")
    void evaluate_age11_isTeen() {
      PricingResult result = pricingService.evaluate(DECISION_RULE,
          new PricingQuery(List.of(new Customer("Teen", 11))));
      assertThat(result.customerTickets().get(0).ticketType()).isEqualTo("Teen");
    }

    @Test
    @DisplayName("age 17 (upper bound of Teen): classified as 'Teen'")
    void evaluate_age17_isTeen() {
      PricingResult result = pricingService.evaluate(DECISION_RULE,
          new PricingQuery(List.of(new Customer("Teen", 17))));
      assertThat(result.customerTickets().get(0).ticketType()).isEqualTo("Teen");
    }

    @Test
    @DisplayName("age 18 (lower bound of Adult): classified as 'Adult'")
    void evaluate_age18_isAdult() {
      PricingResult result = pricingService.evaluate(DECISION_RULE,
          new PricingQuery(List.of(new Customer("Adult", 18))));
      assertThat(result.customerTickets().get(0).ticketType()).isEqualTo("Adult");
    }

    @Test
    @DisplayName("age 64 (upper bound of Adult): classified as 'Adult'")
    void evaluate_age64_isAdult() {
      PricingResult result = pricingService.evaluate(DECISION_RULE,
          new PricingQuery(List.of(new Customer("Adult", 64))));
      assertThat(result.customerTickets().get(0).ticketType()).isEqualTo("Adult");
    }

    @Test
    @DisplayName("age 65 (lower bound of Senior): classified as 'Senior'")
    void evaluate_age65_isSenior() {
      PricingResult result = pricingService.evaluate(DECISION_RULE,
          new PricingQuery(List.of(new Customer("Senior", 65))));
      assertThat(result.customerTickets().get(0).ticketType()).isEqualTo("Senior");
    }
  }

  // ---------------------------------------------------------------------------
  // Edge / error cases
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Mixed group")
  class MixedGroup {

    @Test
    @DisplayName("all four types: correct ticket count and childrenCount")
    void evaluate_mixedGroup_producesCorrectResultShape() {
      // Alice (35) Adult, Eve (15) Teen, Bob (10) + Dan (7) + Dis (8) Children, Carol (68) Senior
      PricingQuery query = new PricingQuery(List.of(
          new Customer("Alice", 35),
          new Customer("Eve", 15),
          new Customer("Bob", 10),
          new Customer("Carol", 68),
          new Customer("Dan", 7),
          new Customer("Dis", 8)
      ));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      assertThat(result.customerTickets()).hasSize(6);
      assertThat(result.childrenCount()).isEqualTo(3);  // Bob (10), Dan (7), Dis (8)
      assertThat(result.childrenDiscountMultiplier()).isEqualTo(CHILDREN_MULTIPLIER);
      assertThat(result.seniorDiscountMultiplier()).isEqualTo(SENIOR_MULTIPLIER);
    }

    @Test
    @DisplayName("all tickets in a mixed group have a positive finalPrice")
    void evaluate_mixedGroup_allTicketsHavePositiveFinalPrice() {
      PricingQuery query = new PricingQuery(List.of(
          new Customer("Alice", 35),
          new Customer("Eve", 15),
          new Customer("Bob", 10),
          new Customer("Carol", 68)
      ));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      result.customerTickets().forEach(ticket ->
          assertThat(ticket.finalPrice())
              .as("finalPrice for %s should be positive", ticket.name())
              .isGreaterThan(BigDecimal.ZERO)
      );
    }

    @Test
    @DisplayName("Adult finalPrice equals basePrice — no discount applied")
    void evaluate_adultFinalPriceEqualsBasePrice() {
      PricingQuery query = new PricingQuery(List.of(new Customer("Alice", 35)));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      CustomerTicket ticket = result.customerTickets().get(0);
      assertThat(ticket.finalPrice()).isEqualByComparingTo(ticket.basePrice());
    }

    @Test
    @DisplayName("Teen finalPrice equals basePrice — no discount applied")
    void evaluate_teenFinalPriceEqualsBasePrice() {
      PricingQuery query = new PricingQuery(List.of(new Customer("Eve", 15)));

      PricingResult result = pricingService.evaluate(DECISION_RULE, query);

      CustomerTicket ticket = result.customerTickets().get(0);
      assertThat(ticket.finalPrice()).isEqualByComparingTo(ticket.basePrice());
    }
  }

  // ---------------------------------------------------------------------------
  // Test-scoped Spring configuration
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Edge / error cases")
  class EdgeCases {

    @Test
    @DisplayName("throws RuntimeException for a nonexistent decision rule")
    void evaluate_unknownDecisionRule_throwsRuntimeException() {
      PricingQuery query = new PricingQuery(List.of(new Customer("Alice", 35)));

      assertThatThrownBy(() -> pricingService.evaluate("nonexistent-rule.json", query))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Rule evaluation failed for: nonexistent-rule.json");
    }
  }
}