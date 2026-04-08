package com.sevnis.movieticket.pricing;

import com.sevnis.movieticket.pricing.configuration.GoruleProperties;
import com.sevnis.movieticket.pricing.dto.Customer;
import com.sevnis.movieticket.pricing.dto.CustomerTicket;
import com.sevnis.movieticket.pricing.dto.PricingQuery;
import com.sevnis.movieticket.pricing.dto.PricingResult;
import com.sevnis.movieticket.pricing.requests.TransactionRequest;
import com.sevnis.movieticket.pricing.responses.TicketSummary;
import com.sevnis.movieticket.pricing.responses.TransactionResponse;
import com.sevnis.movieticket.pricing.service.PricingService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/api/{version}/pricing")
@RequiredArgsConstructor
@RestController
public class PricingController {

  private final PricingService pricingService;
  private final GoruleProperties goruleProperties;

  @PostMapping(version = "1.0")
  public TransactionResponse getTicketPrices(
      @Valid @RequestBody TransactionRequest transactionRequest) {

    // get pricing of tickets per customer
    PricingResult pricingResult = pricingService.evaluate(goruleProperties.pricingRule(),
        PricingQuery.builder()
            .customers(
                transactionRequest.customers().stream()
                    .map(req -> new Customer(req.name(), req.age()))
                    .toList())
            .build());

    // transform to the aggregated response format
    List<TicketSummary> customerTickets = pricingResult.customerTickets().stream()
        .collect(Collectors.groupingBy(CustomerTicket::ticketType))
        .entrySet().stream()
        .map(entry -> TicketSummary.builder()
            .ticketType(entry.getKey())
            .quantity(entry.getValue().size())
            .totalCost(entry.getValue().stream()
                .map(CustomerTicket::finalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
            .build())
        .sorted(Comparator.comparing(TicketSummary::ticketType))
        .toList();

    return TransactionResponse.builder()
        .transactionId(transactionRequest.transactionId())
        .tickets(customerTickets)
        .totalCost(customerTickets.stream()
            .map(TicketSummary::totalCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add))
        .build();
  }
}
