package com.sevnis.movieticket.pricing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sevnis.movieticket.pricing.PricingController;
import com.sevnis.movieticket.pricing.configuration.GoruleProperties;
import com.sevnis.movieticket.pricing.dto.Customer;
import com.sevnis.movieticket.pricing.dto.CustomerTicket;
import com.sevnis.movieticket.pricing.dto.PricingQuery;
import com.sevnis.movieticket.pricing.dto.PricingResult;
import com.sevnis.movieticket.pricing.requests.CustomerRequest;
import com.sevnis.movieticket.pricing.requests.TransactionRequest;
import com.sevnis.movieticket.pricing.service.PricingService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
@WebMvcTest(PricingController.class)
public class PricingControllerTest {

  private static final BigDecimal ADULT_PRICE = BigDecimal.valueOf(25.00);
  private static final BigDecimal SENIOR_PRICE = BigDecimal.valueOf(17.50);
  private static final BigDecimal TEEN_PRICE = BigDecimal.valueOf(12.00);
  private static final BigDecimal CHILDREN_PRICE = BigDecimal.valueOf(5.00);
  private static final BigDecimal CHILDREN_BULK_PRICE = BigDecimal.valueOf(3.75);

  private static final double SENIOR_MULTIPLIER = 0.70;
  private static final double CHILDREN_MULTIPLIER = 0.75;

  private static final String URL = "/api/1.0/pricing";
  private static final String PRICING_RULE = "pricing.json";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private PricingService pricingService;
  @MockitoBean
  private GoruleProperties goruleProperties;

  private static TransactionRequest singleCustomerRequest(Long txnId, String name, int age) {
    return new TransactionRequest(txnId, List.of(new CustomerRequest(name, age)));
  }

  /**
   * One customer of each ticket type — Alice (Adult), Bob (Children), Diana (Senior), Eve (Teen)
   */
  private static TransactionRequest allFourTypesRequest(Long txnId) {
    return new TransactionRequest(txnId, List.of(
        new CustomerRequest("Alice", 35),
        new CustomerRequest("Bob", 8),
        new CustomerRequest("Diana", 68),
        new CustomerRequest("Eve", 15)
    ));
  }

  /**
   * Alice (35) Adult $25.00 + Bob (8) Children $5.00 + Diana (68) Senior $17.50 + Eve (15) Teen
   * $12.00 = $59.50
   */
  private static PricingResult allFourTypesPricingResult() {
    return new PricingResult(1, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
        new CustomerTicket("Alice", 35, "Adult", ADULT_PRICE, ADULT_PRICE),
        new CustomerTicket("Bob", 8, "Children", CHILDREN_PRICE, CHILDREN_PRICE),
        new CustomerTicket("Diana", 68, "Senior", ADULT_PRICE, SENIOR_PRICE),
        new CustomerTicket("Eve", 15, "Teen", TEEN_PRICE, TEEN_PRICE)
    ));
  }

  private static PricingResult singleTicketResult(
      String name, int age, String type, BigDecimal base, BigDecimal finalP) {
    return new PricingResult(
        "Children".equals(type) ? 1 : 0,
        CHILDREN_MULTIPLIER,
        SENIOR_MULTIPLIER,
        List.of(new CustomerTicket(name, age, type, base, finalP))
    );
  }

  @BeforeEach
  void setUp() {
    when(goruleProperties.pricingRule()).thenReturn(PRICING_RULE);
  }

  @Nested
  @DisplayName("POST /api/1.0/pricing — success")
  class SuccessScenarios {

    @Test
    @DisplayName("single ADULT (age 35): $25.00 flat, no discount")
    void getTicketPrices_singleAdult_returns25() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Alice", 35, "ADULT", ADULT_PRICE, ADULT_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Alice", 35))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("ADULT"))
          .andExpect(jsonPath("$.tickets[0].quantity").value(1))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(25.00))
          .andExpect(jsonPath("$.totalCost").value(25.00));
    }

    @Test
    @DisplayName("single SENIOR (age 68): $17.50 (30% off adult $25.00)")
    void getTicketPrices_singleSenior_returns17_50() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Carol", 68, "SENIOR", ADULT_PRICE, SENIOR_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Carol", 68))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("SENIOR"))
          .andExpect(jsonPath("$.tickets[0].quantity").value(1))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(17.50))
          .andExpect(jsonPath("$.totalCost").value(17.50));
    }

    @Test
    @DisplayName("single TEEN (age 15): $12.00 flat, no discount")
    void getTicketPrices_singleTeen_returns12() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Eve", 15, "TEEN", TEEN_PRICE, TEEN_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Eve", 15))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("TEEN"))
          .andExpect(jsonPath("$.tickets[0].quantity").value(1))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(12.00))
          .andExpect(jsonPath("$.totalCost").value(12.00));
    }

    @Test
    @DisplayName("single CHILDREN (age 8): $5.00 flat, no bulk discount (only 1 child)")
    void getTicketPrices_singleChild_returns5() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Bob", 8, "Children", CHILDREN_PRICE, CHILDREN_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Bob", 8))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("Children"))
          .andExpect(jsonPath("$.tickets[0].quantity").value(1))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(5.00))
          .andExpect(jsonPath("$.totalCost").value(5.00));
    }

    @Test
    @DisplayName("2 children: $5.00 each — bulk discount does NOT apply (threshold is 3+)")
    void getTicketPrices_twoChildren_noDiscount_returns10() throws Exception {
      PricingResult result = new PricingResult(2, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
          new CustomerTicket("Bob", 8, "Children", CHILDREN_PRICE, CHILDREN_PRICE),
          new CustomerTicket("Dave", 7, "Children", CHILDREN_PRICE, CHILDREN_PRICE)
      ));
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class))).thenReturn(result);

      TransactionRequest request = new TransactionRequest(1L, List.of(
          new CustomerRequest("Bob", 8),
          new CustomerRequest("Dave", 7)
      ));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          // 2 children × $5.00 = $10.00 — no discount
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Children')].quantity").value(2))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Children')].totalCost").value(10.00))
          .andExpect(jsonPath("$.totalCost").value(10.00));
    }

    @Test
    @DisplayName("exactly 3 children: 25% bulk discount applies — $3.75 each, $11.25 total")
    void getTicketPrices_threeChildren_bulkDiscountApplies_returns11_25() throws Exception {
      PricingResult result = new PricingResult(3, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
          new CustomerTicket("Bob", 8, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
          new CustomerTicket("Dave", 7, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
          new CustomerTicket("Ella", 5, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE)
      ));
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class))).thenReturn(result);

      TransactionRequest request = new TransactionRequest(1L, List.of(
          new CustomerRequest("Bob", 8),
          new CustomerRequest("Dave", 7),
          new CustomerRequest("Ella", 5)
      ));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          // 3 children × $3.75 = $11.25
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Children')].quantity").value(3))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Children')].totalCost").value(11.25))
          .andExpect(jsonPath("$.totalCost").value(11.25));
    }

    @Test
    @DisplayName("4 children: bulk discount still applies — $3.75 each, $15.00 total")
    void getTicketPrices_fourChildren_bulkDiscountApplies_returns15() throws Exception {
      PricingResult result = new PricingResult(4, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
          new CustomerTicket("Bob", 8, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
          new CustomerTicket("Dave", 7, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
          new CustomerTicket("Ella", 5, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE),
          new CustomerTicket("Fred", 3, "Children", CHILDREN_PRICE, CHILDREN_BULK_PRICE)
      ));
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class))).thenReturn(result);

      TransactionRequest request = new TransactionRequest(1L, List.of(
          new CustomerRequest("Bob", 8),
          new CustomerRequest("Dave", 7),
          new CustomerRequest("Ella", 5),
          new CustomerRequest("Fred", 3)
      ));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Children')].quantity").value(4))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Children')].totalCost").value(15.00))
          .andExpect(jsonPath("$.totalCost").value(15.00));
    }

    @Test
    @DisplayName("all four ticket types: correct grouping, quantities, and totals")
    void getTicketPrices_allFourTypes_groupsAndSumsCorrectly() throws Exception {
      // Alice (35) ADULT $25.00 + Bob (8) CHILDREN $5.00 + Diana (68) SENIOR $17.50
      // + Eve (15) TEEN $12.00 = $59.50 total
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(allFourTypesPricingResult());

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(allFourTypesRequest(99L))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transactionId").value(99))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Adult')].quantity").value(1))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Adult')].totalCost").value(25.00))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Senior')].quantity").value(1))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Senior')].totalCost").value(17.50))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Teen')].quantity").value(1))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Teen')].totalCost").value(12.00))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Children')].quantity").value(1))
          .andExpect(jsonPath("$.tickets[?(@.ticketType=='Children')].totalCost").value(5.00))
          .andExpect(jsonPath("$.totalCost").value(59.50));
    }

    @Test
    @DisplayName("all same type: 3 adults produce a single Adult group at $75.00")
    void getTicketPrices_allAdults_returnsSingleGroup() throws Exception {
      PricingResult result = new PricingResult(0, CHILDREN_MULTIPLIER, SENIOR_MULTIPLIER, List.of(
          new CustomerTicket("Alice", 30, "Adult", ADULT_PRICE, ADULT_PRICE),
          new CustomerTicket("Bob", 40, "Adult", ADULT_PRICE, ADULT_PRICE),
          new CustomerTicket("Carol", 25, "Adult", ADULT_PRICE, ADULT_PRICE)
      ));
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class))).thenReturn(result);

      TransactionRequest request = new TransactionRequest(5L, List.of(
          new CustomerRequest("Alice", 30),
          new CustomerRequest("Bob", 40),
          new CustomerRequest("Carol", 25)
      ));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets.length()").value(1))
          .andExpect(jsonPath("$.tickets[0].ticketType").value("Adult"))
          .andExpect(jsonPath("$.tickets[0].quantity").value(3))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(75.00))
          .andExpect(jsonPath("$.totalCost").value(75.00));
    }

    @Test
    @DisplayName("transactionId in response is taken from the request, not PricingResult")
    void getTicketPrices_transactionId_sourceIsRequest() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Alice", 30, "Adult", ADULT_PRICE, ADULT_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(7777L, "Alice", 30))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transactionId").value(7777));
    }

    @Test
    @DisplayName("totalCost equals the sum of all individual TicketSummary totalCosts")
    void getTicketPrices_totalCost_isSumOfAllGroups() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(allFourTypesPricingResult());

      MvcResult mvcResult = mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(allFourTypesRequest(1L))))
          .andExpect(status().isOk())
          .andReturn();

      var response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
      double totalCost = response.get("totalCost").asDouble();
      double ticketSum = 0;
      for (var ticket : response.get("tickets")) {
        ticketSum += ticket.get("totalCost").asDouble();
      }
      assertThat(totalCost).isEqualTo(ticketSum);
    }
  }

  @Nested
  @DisplayName("Age boundary classification")
  class AgeBoundaries {

    @Test
    @DisplayName("age 10 (upper boundary of CHILDREN): classified as Children at $5.00")
    void getTicketPrices_age10_isChildren() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Kid", 10, "Children", CHILDREN_PRICE, CHILDREN_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Kid", 10))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("Children"))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(5.00));
    }

    @Test
    @DisplayName("age 11 (lower boundary of Teen): classified as Teen at $12.00")
    void getTicketPrices_age11_isTeen() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Teen", 11, "Teen", TEEN_PRICE, TEEN_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Teen", 11))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("Teen"))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(12.00));
    }

    @Test
    @DisplayName("age 17 (upper boundary of Teen): classified as Teen at $12.00")
    void getTicketPrices_age17_isTeen() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Teen", 17, "Teen", TEEN_PRICE, TEEN_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Teen", 17))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("Teen"))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(12.00));
    }

    @Test
    @DisplayName("age 18 (lower boundary of Adult): classified as ADULT at $25.00")
    void getTicketPrices_age18_isAdult() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Adult", 18, "Adult", ADULT_PRICE, ADULT_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Adult", 18))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("Adult"))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(25.00));
    }

    @Test
    @DisplayName("age 64 (upper boundary of Adult): classified as Adult at $25.00")
    void getTicketPrices_age64_isAdult() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Adult", 64, "Adult", ADULT_PRICE, ADULT_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Adult", 64))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("Adult"))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(25.00));
    }

    @Test
    @DisplayName("age 65 (lower boundary of Senior): classified as Senior at $17.50")
    void getTicketPrices_age65_isSenior() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Senior", 65, "Senior", ADULT_PRICE, SENIOR_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Senior", 65))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tickets[0].ticketType").value("Senior"))
          .andExpect(jsonPath("$.tickets[0].totalCost").value(17.50));
    }
  }

  @Nested
  @DisplayName("Service delegation")
  class ServiceDelegation {

    @Test
    @DisplayName("forwards the correct customer list to PricingService")
    void getTicketPrices_forwardsCorrectCustomers_toPricingService() throws Exception {
      TransactionRequest request = new TransactionRequest(1L, List.of(
          new CustomerRequest("Alice", 35),
          new CustomerRequest("Bob", 10)
      ));
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Alice", 35, "Adult", ADULT_PRICE, ADULT_PRICE));

      mockMvc.perform(post(URL)
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(request)));

      ArgumentCaptor<PricingQuery> queryCaptor = ArgumentCaptor.forClass(PricingQuery.class);
      verify(pricingService).evaluate(eq(PRICING_RULE), queryCaptor.capture());

      List<Customer> forwarded = queryCaptor.getValue().customers();
      assertThat(forwarded).hasSize(2);
      assertThat(forwarded.get(0).name()).isEqualTo("Alice");
      assertThat(forwarded.get(0).age()).isEqualTo(35);
      assertThat(forwarded.get(1).name()).isEqualTo("Bob");
      assertThat(forwarded.get(1).age()).isEqualTo(10);
    }

    @Test
    @DisplayName("uses the pricing rule name from GoruleProperties")
    void getTicketPrices_usesRuleName_fromGoruleProperties() throws Exception {
      String customRule = "vip-pricing.json";
      when(goruleProperties.pricingRule()).thenReturn(customRule);
      when(pricingService.evaluate(eq(customRule), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Alice", 35, "Adult", ADULT_PRICE, ADULT_PRICE));

      mockMvc.perform(post(URL)
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Alice", 35))));

      verify(pricingService).evaluate(eq(customRule), any(PricingQuery.class));
    }
  }

  @Nested
  @DisplayName("Request validation — transactionId")
  class TransactionIdValidation {

    @Test
    @DisplayName("returns 400 when transactionId is null")
    void getTicketPrices_nullTransactionId_returns400() throws Exception {
      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"customers\": [{\"name\": \"Alice\", \"age\": 30}]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 400 when transactionId is zero (@Min(1))")
    void getTicketPrices_zeroTransactionId_returns400() throws Exception {
      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"transactionId\": 0, \"customers\": [{\"name\": \"Alice\", \"age\": 30}]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 400 when transactionId is negative (@Min(1))")
    void getTicketPrices_negativeTransactionId_returns400() throws Exception {
      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"transactionId\": -5, \"customers\": [{\"name\": \"Alice\", \"age\": 30}]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("accepts transactionId of 1 (boundary value of @Min(1))")
    void getTicketPrices_transactionIdOfOne_isAccepted() throws Exception {
      when(pricingService.evaluate(eq(PRICING_RULE), any(PricingQuery.class)))
          .thenReturn(singleTicketResult("Alice", 30, "Adult", ADULT_PRICE, ADULT_PRICE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Alice", 30))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transactionId").value(1));
    }
  }

  @Nested
  @DisplayName("Request validation — customers")
  class CustomersValidation {

    @Test
    @DisplayName("returns 400 when customers list is null (@NotNull)")
    void getTicketPrices_nullCustomers_returns400() throws Exception {
      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"transactionId\": 1}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 400 when customers list is empty (@Size(min=1))")
    void getTicketPrices_emptyCustomers_returns400() throws Exception {
      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"transactionId\": 1, \"customers\": []}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 400 when a nested CustomerRequest fails @Valid")
    void getTicketPrices_invalidCustomer_returns400() throws Exception {
      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"transactionId\": 1, \"customers\": [{\"name\": \"Alice\"}]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 415 when Content-Type is not JSON")
    void getTicketPrices_wrongContentType_returns415() throws Exception {
      mockMvc.perform(post(URL)
              .contentType(MediaType.TEXT_PLAIN)
              .content("some text"))
          .andExpect(status().isUnsupportedMediaType());
    }
  }

  @Nested
  @DisplayName("Error handling")
  class ErrorScenarios {

    @Test
    @DisplayName("returns 500 when PricingService throws RuntimeException")
    void getTicketPrices_serviceThrows_returns500() throws Exception {
      when(pricingService.evaluate(any(), any()))
          .thenThrow(new RuntimeException("Rule evaluation failed for: " + PRICING_RULE));

      mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(singleCustomerRequest(1L, "Alice", 35))))
          .andExpect(status().isInternalServerError());
    }
  }
}