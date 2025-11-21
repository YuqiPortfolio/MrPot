package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.util.CacheKeyUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PromptCacheService {

  private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

  public Optional<CacheEntry> hitIfPresent(String key) {
    String normalizedKey = CacheKeyUtils.normalizeKey(key);
    if (normalizedKey == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(cache.computeIfPresent(normalizedKey, (k, entry) -> entry.hit()));
  }

  public Optional<CacheEntry> store(String key, String systemPrompt, String userPrompt, String finalPrompt) {
    String normalizedKey = CacheKeyUtils.normalizeKey(key);
    if (normalizedKey == null) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    CacheEntry entry = cache.compute(normalizedKey, (k, existing) -> {
      if (existing == null) {
        return new CacheEntry(k, systemPrompt, userPrompt, finalPrompt, 1, now, now);
      }
      return existing.update(systemPrompt, userPrompt, finalPrompt);
    });
    return Optional.ofNullable(entry);
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
