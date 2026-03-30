package com.sevnis.movieticket.pricing.responses;

import java.math.BigDecimal;
import lombok.Builder;

@Builder
public record TicketSummary(
    String ticketType,
    Integer quantity,
    BigDecimal totalCost
) {

}
