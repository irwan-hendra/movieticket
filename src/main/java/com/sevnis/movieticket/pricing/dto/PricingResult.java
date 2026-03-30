package com.sevnis.movieticket.pricing.dto;

import java.util.List;

public record PricingResult(
    Integer childrenCount,
    Double childrenDiscountMultiplier,
    Double seniorDiscountMultiplier,
    List<CustomerTicket> customerTickets
) {


}
