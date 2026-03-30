package com.sevnis.movieticket.pricing.responses;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;


@Builder
public record TransactionResponse(
    Long transactionId,
    List<TicketSummary> tickets,
    BigDecimal totalCost
) {

}
