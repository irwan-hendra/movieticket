package com.sevnis.movieticket.pricing.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gorules.zen_engine.JsonBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ZenEngineConfig — decision loader")
public class ZenEngineConfigTest {

  private RulesCache rulesCache;
  private ZenEngineConfig zenEngineConfig;

  @BeforeEach
  void setUp() {
    rulesCache = mock(RulesCache.class);
    zenEngineConfig = new ZenEngineConfig(rulesCache);
  }

  @Test
  @DisplayName("returns a completed future containing a JsonBuffer when the cache returns bytes")
  void returnsCompletedFutureWithJsonBufferOnSuccess() throws Exception {
    byte[] ruleBytes = """
        { "nodes": [], "edges": [] }
        """.getBytes(StandardCharsets.UTF_8);
    when(rulesCache.get("pricing.json")).thenReturn(ruleBytes);

    CompletableFuture<JsonBuffer> result = zenEngineConfig.loadDecision("pricing.json");

    assertThat(result).isCompleted();
    assertThat(result.get()).isNotNull();
    verify(rulesCache).get("pricing.json");
  }

  @Test
  @DisplayName("returns a failed future when the cache throws an exception")
  void returnsFailedFutureWhenCacheThrows() {
    var cause = new RuntimeException("file not found");
    when(rulesCache.get("missing.json")).thenThrow(cause);

    CompletableFuture<JsonBuffer> result = zenEngineConfig.loadDecision("missing.json");

    assertThat(result).isCompletedExceptionally();
    assertThatThrownBy(result::get)
        .cause()
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Decision Loader failed for key: missing.json")
        .hasMessageContaining("file not found");
  }

  @Test
  @DisplayName("preserves the original exception as the cause in the failure chain")
  void wrapsOriginalExceptionAsCause() {
    var original = new RuntimeException("disk read error");
    when(rulesCache.get("broken.json")).thenThrow(original);

    CompletableFuture<JsonBuffer> result = zenEngineConfig.loadDecision("broken.json");

    assertThatThrownBy(result::get)
        .cause()
        .hasCause(original);  // original exception is preserved in the chain
  }

  @Test
  @DisplayName("includes the rule key in the error message to aid debugging")
  void includesKeyInErrorMessage() {
    when(rulesCache.get("tax-rules.json"))
        .thenThrow(new RuntimeException("access denied"));

    CompletableFuture<JsonBuffer> result = zenEngineConfig.loadDecision("tax-rules.json");

    assertThatThrownBy(result::get)
        .cause()
        .hasMessageContaining("tax-rules.json");
  }
}