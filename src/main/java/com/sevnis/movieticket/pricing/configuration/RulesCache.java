package com.sevnis.movieticket.pricing.configuration;

import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RulesCache {

  private final GoruleProperties goruleProperties;
  private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

  // load the rules from memory to improve performance
  public byte[] get(String key) {
    return cache.computeIfAbsent(key, k -> {
      String resourcePath = goruleProperties.rulesPath() + key;
      try (InputStream inputStream = new ClassPathResource(resourcePath).getInputStream()) {
        return inputStream.readAllBytes();
      } catch (Exception e) {
        throw new RuntimeException("Failed to load rule from classpath: " + resourcePath, e);
      }
    });
  }

  // can be used to do hot reloading of the rules
  public void evict(String key) {
    cache.remove(key);
  }

  public void evictAll() {
    cache.clear();
  }
}
