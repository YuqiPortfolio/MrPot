package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.service.PromptCacheService;
import com.example.datalake.mrpot.util.CacheKeyUtils;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptCacheRecordProcessor implements TextProcessor {

  private final PromptCacheService cacheService;

  @Override
  public String name() {
    return "prompt-cache-record";
  }

  @Override
  public Mono<ProcessingContext> process(ProcessingContext ctx) {
    if (ctx.isCommonResponse()) {
      return Mono.just(ctx.addStep(name(), "skip-common"));
    }
    if (ctx.isCacheHit()) {
      return Mono.just(ctx.addStep(name(), "skip-hit freq=" + ctx.getCacheFrequency()));
    }

    String key = CacheKeyUtils.normalizeKey(ctx.getCacheKey());
    if (key == null) key = CacheKeyUtils.buildKey(ctx);
    if (key == null || key.isBlank()) {
      return Mono.just(ctx.addStep(name(), "no-key"));
    }

    String systemPrompt = PromptRenderUtils.ensureSystemPrompt(ctx);
    String userPrompt = PromptRenderUtils.ensureUserPrompt(ctx);
    String finalPrompt = PromptRenderUtils.ensureFinalPrompt(ctx);
    if (finalPrompt == null || finalPrompt.isBlank()) {
      return Mono.just(ctx.addStep(name(), "skip-empty"));
    }

    return cacheService.store(key, systemPrompt, userPrompt, finalPrompt)
        .map(entry -> {
          ctx.setCacheFrequency(entry.frequency());
          log.debug("Recorded cache entry for key={} freq={}.", key, entry.frequency());
          return ctx.addStep(name(), "record freq=" + entry.frequency());
        })
        .map(Mono::just)
        .orElseGet(() -> Mono.just(ctx.addStep(name(), "skip-empty-key")));
  }
}
