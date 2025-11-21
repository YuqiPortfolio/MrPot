package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.service.PromptCacheService;
import com.example.datalake.mrpot.util.CacheKeyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptCacheLookupProcessor implements TextProcessor {

  private final PromptCacheService cacheService;

  @Override
  public String name() {
    return "prompt-cache-lookup";
  }

  @Override
  public Mono<ProcessingContext> process(ProcessingContext ctx) {
    if (ctx.isCommonResponse()) {
      return Mono.just(ctx.addStep(name(), "skip-common"));
    }

    String key = CacheKeyUtils.buildKey(ctx);
    ctx.setCacheKey(key);
    if (key == null || key.isBlank()) {
      return Mono.just(ctx.addStep(name(), "no-key"));
    }

    Optional<PromptCacheService.CacheEntry> hit = cacheService.hitIfPresent(key);
    if (hit.isEmpty()) {
      return Mono.just(ctx.addStep(name(), "miss"));
    }

    PromptCacheService.CacheEntry entry = hit.get();
    ctx.setCacheHit(true);
    ctx.setCacheFrequency(entry.frequency());
    if (entry.systemPrompt() != null) {
      ctx.setSystemPrompt(entry.systemPrompt());
    }
    if (entry.userPrompt() != null) {
      ctx.setUserPrompt(entry.userPrompt());
    }
    if (entry.finalPrompt() != null) {
      ctx.setFinalPrompt(entry.finalPrompt());
    }

    log.debug("Cache hit for key={} freq={}.", key, entry.frequency());
    return Mono.just(ctx.addStep(name(), "hit freq=" + entry.frequency()));
  }
}
