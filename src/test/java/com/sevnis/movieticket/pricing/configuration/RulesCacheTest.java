package com.sevnis.movieticket.pricing.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RulesCache — classpath-backed decision file cache")
public class RulesCacheTest {

  private GoruleProperties goruleProperties;
  private RulesCache rulesCache;

  @BeforeEach
  void setUp() {
    goruleProperties = mock(GoruleProperties.class);
    when(goruleProperties.rulesPath()).thenReturn("rules/");
    rulesCache = new RulesCache(goruleProperties);
  }

  @Test
  @DisplayName("loads and returns resource bytes from the classpath on a cache miss")
  void loadsResourceFromClasspathOnCacheMiss() {
    byte[] result = rulesCache.get("pricing.json");
    assertThat(result).isNotEmpty();
  }

  @Test
  @DisplayName("returns the same cached instance on subsequent calls without reloading")
  void returnsCachedValueWithoutReloadingOnCacheHit() {
    byte[] firstCall = rulesCache.get("pricing.json");
    byte[] secondCall = rulesCache.get("pricing.json");

    // same reference proves computeIfAbsent didn't re-execute the lambda
    assertThat(firstCall).isSameAs(secondCall);
    // GoruleProperties was only accessed once (during the first load)
    verify(goruleProperties, times(1)).rulesPath();
  }

  @Test
  @DisplayName("throws RuntimeException when the requested resource does not exist on the classpath")
  void throwsRuntimeExceptionForMissingResource() {
    assertThatThrownBy(() -> rulesCache.get("nonexistent.json"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("evict removes a single entry so the next get reloads it from the classpath")
  void evictRemovesEntryFromCache() {
    rulesCache.get("pricing.json");
    rulesCache.evict("pricing.json");

    // after eviction, rulesPath() will be called again on next get()
    rulesCache.get("pricing.json");
    verify(goruleProperties, times(2)).rulesPath();
  }

  @Test
  @DisplayName("evictAll clears the entire cache so all subsequent gets reload from the classpath")
  void evictAllClearsEntireCache() {
    rulesCache.get("pricing.json");
    rulesCache.evictAll();

    rulesCache.get("pricing.json");
    verify(goruleProperties, times(2)).rulesPath();
  }
}