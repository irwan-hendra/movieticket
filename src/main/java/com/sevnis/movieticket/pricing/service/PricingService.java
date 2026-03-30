package com.sevnis.movieticket.pricing.service;

import com.sevnis.movieticket.pricing.dto.PricingQuery;
import com.sevnis.movieticket.pricing.dto.PricingResult;
import io.gorules.zen_engine.JsonBuffer;
import io.gorules.zen_engine.ZenEngine;
import io.gorules.zen_engine.ZenEngineResponse;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

  private final ZenEngine zenEngine;
  private final ObjectMapper objectMapper;

  public PricingResult evaluate(String decisionRule, PricingQuery pricingQuery) {
    try {
      log.debug("Evaluating decision [{}] with context: {}", decisionRule, pricingQuery);

      // invoke Gorules Engine
      ZenEngineResponse zenEngineResponse = zenEngine.evaluate(decisionRule,
          new JsonBuffer(objectMapper.writeValueAsBytes(pricingQuery)),
          null).get(10, TimeUnit.SECONDS);

      // convert Gorules Engine result to PricingResult object
      return objectMapper.readValue(Optional.ofNullable(zenEngineResponse)
          .map(ZenEngineResponse::result)
          .map(JsonBuffer::value)
          .orElseThrow(() -> new Exception("Missing Result")), PricingResult.class);
    } catch (Exception e) {
      log.error("Error evaluating decision [{}]: {}", decisionRule, e.getMessage(), e);
      throw new RuntimeException("Rule evaluation failed for: " + decisionRule, e);
    }
  }
}
