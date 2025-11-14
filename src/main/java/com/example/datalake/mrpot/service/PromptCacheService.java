package com.example.datalake.mrpot.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PromptCacheService {

  private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

  public Optional<CacheEntry> hitIfPresent(String key) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(cache.computeIfPresent(key, (k, entry) -> entry.hit()));
  }

  public CacheEntry store(String key, String systemPrompt, String userPrompt, String finalPrompt) {
    if (key == null || key.isBlank()) {
      return new CacheEntry("", systemPrompt, userPrompt, finalPrompt, 1, Instant.now(), Instant.now());
    }
    Instant now = Instant.now();
    return cache.compute(key, (k, existing) -> {
      if (existing == null) {
        return new CacheEntry(k, systemPrompt, userPrompt, finalPrompt, 1, now, now);
      }
      return existing.update(systemPrompt, userPrompt, finalPrompt);
    });
  }

  public record CacheEntry(
      String key,
      String systemPrompt,
      String userPrompt,
      String finalPrompt,
      int frequency,
      Instant firstSeen,
      Instant lastSeen
  ) {
    private CacheEntry hit() {
      return new CacheEntry(key, systemPrompt, userPrompt, finalPrompt, frequency + 1, firstSeen, Instant.now());
    }

    private CacheEntry update(String systemPrompt, String userPrompt, String finalPrompt) {
      return new CacheEntry(key, systemPrompt, userPrompt, finalPrompt, frequency + 1, firstSeen, Instant.now());
    }
  }
}
