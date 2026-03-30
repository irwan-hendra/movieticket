package com.sevnis.movieticket.pricing.configuration;

import io.gorules.zen_engine.JsonBuffer;
import io.gorules.zen_engine.ZenEngine;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GoruleProperties.class)
@RequiredArgsConstructor
public class ZenEngineConfig {

  private final RulesCache rulesCache;

  @Bean
  public ZenEngine zenEngine() {
    return new ZenEngine(this::loadDecision, null);
  }

  // dynamic loading as per Gorules documentation
  CompletableFuture<JsonBuffer> loadDecision(String key) {
    try {
      byte[] bytes = rulesCache.get(key);
      return CompletableFuture.completedFuture(new JsonBuffer(bytes));
    } catch (Exception e) {
      return CompletableFuture.failedFuture(new RuntimeException(
          "Decision Loader failed for key: " + key + " with message: " + e.getMessage(), e));
    }
  }
}

