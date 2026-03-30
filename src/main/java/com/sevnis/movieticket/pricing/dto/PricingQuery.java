package com.sevnis.movieticket.pricing.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record PricingQuery(
    List<Customer> customers
) {

}
