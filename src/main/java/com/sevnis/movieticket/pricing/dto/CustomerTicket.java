package com.sevnis.movieticket.pricing.dto;

import java.math.BigDecimal;

public record CustomerTicket(
    String name,
    Integer age,
    String ticketType,
    BigDecimal basePrice,
    BigDecimal finalPrice
) {

}
