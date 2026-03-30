package com.sevnis.movieticket.pricing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sevnis.movieticket.pricing.configuration.GoruleProperties;
import com.sevnis.movieticket.pricing.dto.CustomerTicket;
import com.sevnis.movieticket.pricing.dto.PricingQuery;
import com.sevnis.movieticket.pricing.dto.PricingResult;
import com.sevnis.movieticket.pricing.requests.CustomerRequest;
import com.sevnis.movieticket.pricing.requests.TransactionRequest;
import com.sevnis.movieticket.pricing.responses.TicketSummary;
import com.sevnis.movieticket.pricing.responses.TransactionResponse;
import com.sevnis.movieticket.pricing.service.PricingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/*
 * Pricing rules under test:
 *   ADULT    — age 18-64  → $25.00
 *   SENIOR   — age 65+    → $17.50  (30% off adult)
 *   TEEN     — age 11-17  → $12.00
 *   CHILDREN — age 0-10   → $5.00   ($3.75 if 3+ children in transaction — 25% bulk discount)
 */
@SpringBootTest
@AutoConfigureMockMvc
public class PricingControllerIntegrationTest {

  private static final BigDecimal ADULT_PRICE = BigDecimal.valueOf(25.00);
  private static final BigDecimal SENIOR_PRICE = BigDecimal.valueOf(17.50); // 25.00 × 0.70
  private static final BigDecimal TEEN_PRICE = BigDecimal.valueOf(12.00);
  private static final BigDecimal CHILDREN_PRICE = BigDecimal.valueOf(5.00);
  private static final BigDecimal CHILDREN_BULK_PRICE = BigDecimal.valueOf(3.75);  // 5.00 × 0.75

  private static final double SENIOR_MULTIPLIER = 0.70;
  private static final double CHILDREN_MULTIPLIER = 0.75;

  private static final String URL = "/api/1.0/pricing";
  private static final String PRICING_RULE = "movie-pricing.json";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private PricingService pricingService;
  @MockitoBean
  private GoruleProperties goruleProperties;

  @BeforeEach
  void setUp() {
    when(goruleProperties.pricingRule()).thenReturn(PRICING_RULE);
  }

  @Test
  @DisplayName("all four ticket types: correctly grouped with accurate quantities and totals")
  void fullStack_allFourTypes_correctGroupingAndTotals() throws Exception {
    // Alice (35) ADULT $25.00 + Bob (8) CHILDREN $5.00
    // + Charlie (15) TEEN $12.00 + Diana (68) SENIOR $17.50 = $59.50
    TransactionRequest request = new TransactionRequest(42L, List.of(
        new CustomerRequest("Alice", 35),
        new CustomerRequest("Bob", 8),
        new CustomerRequest("Charlie", 15),
        new CustomerRequest("Diana", 68)
    ));
    when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
        .thenReturn(new PricingResult(1, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
            new CustomerTicket("Alice", 35, "Adult", ADULT_PRICE, ADULT_PRICE),
            new CustomerTicket("Bob", 8, "Children", CHILDREN_PRICE, CHILDREN_PRICE),
            new CustomerTicket("Charlie", 15, "Teen", TEEN_PRICE, TEEN_PRICE),
            new CustomerTicket("Diana", 68, "Senior", ADULT_PRICE, SENIOR_PRICE)
        )));

    MvcResult mvcResult = mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andReturn();

    TransactionResponse response = objectMapper.readValue(
        mvcResult.getResponse().getContentAsString(), TransactionResponse.class);

    assertThat(response.transactionId()).isEqualTo(42L);
    assertThat(response.tickets()).hasSize(4);

    var byType = response.tickets().stream()
        .collect(Collectors.toMap(TicketSummary::ticketType, t -> t));

    assertThat(byType.get("Adult").quantity()).isEqualTo(1);
    assertThat(byType.get("Adult").totalCost()).isEqualByComparingTo(BigDecimal.valueOf(25.00));
    assertThat(byType.get("Teen").quantity()).isEqualTo(1);
    assertThat(byType.get("Teen").totalCost()).isEqualByComparingTo(BigDecimal.valueOf(12.00));
    assertThat(byType.get("Children").quantity()).isEqualTo(1);
    assertThat(byType.get("Children").totalCost()).isEqualByComparingTo(BigDecimal.valueOf(5.00));
    assertThat(byType.get("Senior").quantity()).isEqualTo(1);
    assertThat(byType.get("Senior").totalCost()).isEqualByComparingTo(BigDecimal.valueOf(17.50));
    assertThat(response.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(59.50));
  }

  @Test
  @DisplayName("2 children (below bulk threshold): $5.00 each, no discount, $10.00 total")
  void fullStack_twoChildren_noBulkDiscount_returns10() throws Exception {
    TransactionRequest request = new TransactionRequest(1L, List.of(
        new CustomerRequest("Bob", 8),
        new CustomerRequest("Dave", 7)
    ));
    when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
        .thenReturn(new PricingResult(2, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
            new CustomerTicket("Bob", 8, "Children", CHILDREN_PRICE, CHILDREN_PRICE),
            new CustomerTicket("Dave", 7, "Children", CHILDREN_PRICE, CHILDREN_PRICE)
        )));

    MvcResult mvcResult = mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andReturn();

    TransactionResponse response = objectMapper.readValue(
        mvcResult.getResponse().getContentAsString(), TransactionResponse.class);

    var byType = response.tickets().stream()
        .collect(Collectors.toMap(TicketSummary::ticketType, t -> t));

    assertThat(byType.get("Children").quantity()).isEqualTo(2);
    assertThat(byType.get("Children").totalCost()).isEqualByComparingTo(BigDecimal.valueOf(10.00));
    assertThat(response.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(10.00));
  }

  @Test
  @DisplayName("3 children (at bulk threshold): 25% discount — $3.75 each, $11.25 total")
  void fullStack_threeChildren_bulkDiscountApplies_returns11_25() throws Exception {
    TransactionRequest request = new TransactionRequest(1L, List.of(
        new CustomerRequest("Bob", 8),
        new CustomerRequest("Dave", 7),
        new CustomerRequest("Ella", 5)
    ));
    when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
        .thenReturn(new PricingResult(3, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
            new CustomerTicket("Bob", 8, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
            new CustomerTicket("Dave", 7, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
            new CustomerTicket("Ella", 5, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE)
        )));

    MvcResult mvcResult = mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andReturn();

    TransactionResponse response = objectMapper.readValue(
        mvcResult.getResponse().getContentAsString(), TransactionResponse.class);

    var byType = response.tickets().stream()
        .collect(Collectors.toMap(t -> t.ticketType(), t -> t));

    assertThat(byType.get("Children").quantity()).isEqualTo(3);
    assertThat(byType.get("Children").totalCost()).isEqualByComparingTo(BigDecimal.valueOf(11.25));
    assertThat(response.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(11.25));
  }

  @Test
  @DisplayName("mixed adults and children with bulk discount: totals computed correctly")
  void fullStack_adultsAndThreeChildren_bulkDiscountOnChildrenOnly() throws Exception {
    // Alice (35) ADULT $25.00 + 3 children × $3.75 = $25.00 + $11.25 = $36.25
    TransactionRequest request = new TransactionRequest(1L, List.of(
        new CustomerRequest("Alice", 35),
        new CustomerRequest("Bob", 8),
        new CustomerRequest("Dave", 7),
        new CustomerRequest("Ella", 5)
    ));
    when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
        .thenReturn(new PricingResult(3, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
            new CustomerTicket("Alice", 35, "Adult", ADULT_PRICE, ADULT_PRICE),
            new CustomerTicket("Bob", 8, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
            new CustomerTicket("Dave", 7, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
            new CustomerTicket("Ella", 5, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE)
        )));

    MvcResult mvcResult = mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andReturn();

    TransactionResponse response = objectMapper.readValue(
        mvcResult.getResponse().getContentAsString(), TransactionResponse.class);

    var byType = response.tickets().stream()
        .collect(Collectors.toMap(t -> t.ticketType(), t -> t));

    assertThat(byType.get("Adult").totalCost()).isEqualByComparingTo(BigDecimal.valueOf(25.00));
    assertThat(byType.get("Children").quantity()).isEqualTo(3);
    assertThat(byType.get("Children").totalCost()).isEqualByComparingTo(BigDecimal.valueOf(11.25));
    assertThat(response.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(36.25));
  }

  // ---------------------------------------------------------------------------
  // totalCost invariant
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("totalCost always equals the sum of all TicketSummary totalCosts")
  void fullStack_totalCost_equalsTicketSummarySum() throws Exception {
    // Alice (30) ADULT $25.00 + Bob (14) TEEN $12.00 = $37.00
    TransactionRequest request = new TransactionRequest(1L, List.of(
        new CustomerRequest("Alice", 30),
        new CustomerRequest("Bob", 14)
    ));
    when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
        .thenReturn(new PricingResult(0, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
            new CustomerTicket("Alice", 30, "Adult", ADULT_PRICE, ADULT_PRICE),
            new CustomerTicket("Bob", 14, "Teen", TEEN_PRICE, TEEN_PRICE)
        )));

    MvcResult mvcResult = mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andReturn();

    TransactionResponse response = objectMapper.readValue(
        mvcResult.getResponse().getContentAsString(), TransactionResponse.class);

    BigDecimal computedSum = response.tickets().stream()
        .map(t -> t.totalCost())
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    assertThat(response.totalCost()).isEqualByComparingTo(computedSum);
  }

  // ---------------------------------------------------------------------------
  // JSON serialisation contract
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("response contains all expected top-level JSON fields")
  void fullStack_responseShape_containsExpectedFields() throws Exception {
    when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
        .thenReturn(new PricingResult(0, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
            new CustomerTicket("Alice", 30, "Adult", ADULT_PRICE, ADULT_PRICE)
        )));

    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                new TransactionRequest(1L, List.of(new CustomerRequest("Alice", 30))))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transactionId").exists())
        .andExpect(jsonPath("$.tickets").isArray())
        .andExpect(jsonPath("$.totalCost").exists())
        .andExpect(jsonPath("$.tickets[0].ticketType").exists())
        .andExpect(jsonPath("$.tickets[0].quantity").exists())
        .andExpect(jsonPath("$.tickets[0].totalCost").exists());
  }

  // ---------------------------------------------------------------------------
  // Validation — full context picks up MethodArgumentNotValidException handler
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("returns 400 when transactionId is null")
  void fullStack_nullTransactionId_returns400() throws Exception {
    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"customers\": [{\"name\": \"Alice\", \"age\": 30}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("returns 400 when transactionId is zero (boundary: @Min(1))")
  void fullStack_zeroTransactionId_returns400() throws Exception {
    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"transactionId\": 0, \"customers\": [{\"name\": \"Alice\", \"age\": 30}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("returns 400 when transactionId is negative")
  void fullStack_negativeTransactionId_returns400() throws Exception {
    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"transactionId\": -1, \"customers\": [{\"name\": \"Alice\", \"age\": 30}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("returns 400 when customers list is null")
  void fullStack_nullCustomers_returns400() throws Exception {
    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"transactionId\": 1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("returns 400 when customers list is empty (@Size(min=1))")
  void fullStack_emptyCustomers_returns400() throws Exception {
    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"transactionId\": 1, \"customers\": []}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("returns 400 when a nested CustomerRequest fails @Valid")
  void fullStack_invalidNestedCustomer_returns400() throws Exception {
    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"transactionId\": 1, \"customers\": [{\"name\": \"Alice\"}]}"))
        .andExpect(status().isBadRequest());
  }

  // ---------------------------------------------------------------------------
  // Error propagation
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("returns 500 when PricingService throws RuntimeException")
  void fullStack_serviceThrows_returns500() throws Exception {
    when(pricingService.evaluate(any(), any()))
        .thenThrow(new RuntimeException("Rule evaluation failed for: " + PRICING_RULE));

    mockMvc.perform(post(URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                new TransactionRequest(1L, List.of(new CustomerRequest("Alice", 30))))))
        .andExpect(status().isInternalServerError());
  }
}