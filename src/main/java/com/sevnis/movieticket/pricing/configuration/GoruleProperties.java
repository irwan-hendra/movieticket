package com.sevnis.movieticket.pricing.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gorules")
public record GoruleProperties(
    String rulesPath,
    String pricingRule
) {

}
