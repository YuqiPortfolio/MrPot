// src/main/java/com/example/datalake/mrpot/service/PromptPipeline.java
package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.processor.*;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.validation.ValidationContext;
import com.example.datalake.mrpot.validation.ValidationException;
import com.example.datalake.mrpot.validation.ValidationService;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.example.datalake.mrpot.util.PromptRenderUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PromptPipeline {

  // Explicit, deterministic order for the first three processors
  private static final List<Class<? extends TextProcessor>> DEFAULT_ORDER = List.of(
          UnifiedCleanCorrectProcessor.class,
          IntentClassifierProcessor.class,
          CommonResponseProcessor.class,
          PromptCacheLookupProcessor.class,
          PromptTemplateProcessor.class,
          LangChain4jRagProcessor.class,
          PromptCacheRecordProcessor.class
  );


    private final Map<Class<? extends TextProcessor>, TextProcessor> processorsByType;
  private final ValidationService validationService;

  public PromptPipeline(List<TextProcessor> processors, ValidationService validationService) {
    // Use AopUtils.getTargetClass to handle Spring proxies (CGLIB/JDK)
    this.processorsByType = processors.stream()
        .collect(Collectors.toMap(
            p -> getConcreteType(p),
            Function.identity(),
            (left, right) -> left,
            LinkedHashMap::new
        ));
    this.validationService = validationService;
  }

  public Mono<ProcessingContext> run(PrepareRequest request) {
    ProcessingContext ctx;
    try {
      ctx = initializeContext(request);
    } catch (ValidationException ex) {
      return Mono.error(ex);
    }

    Mono<ProcessingContext> pipeline = Mono.just(ctx);
    for (TextProcessor processor : buildOrderedChain()) {
      final TextProcessor stage = processor;
      pipeline = pipeline.flatMap(current -> {
        if (current.isCacheHit() && shouldBypassAfterCache(stage)) {
          return Mono.just(current.addStep(stage.name(), "bypass-cache"));
        }
        return stage.process(current);
      });
    }
    return pipeline;
  }

  /**
   * Stream the pipeline, emitting the {@link ProcessingContext} after each processor completes.
   * This is used by the SSE endpoint to provide real-time progress updates.
   */
  public Flux<ProcessingContext> runStreaming(PrepareRequest request) {
    ProcessingContext ctx;
    try {
      ctx = initializeContext(request);
    } catch (ValidationException ex) {
      return Flux.error(ex);
    }

    // Build a sequential chain while emitting the updated context after *each* processor
    // completes. This ensures SSE consumers receive real-time step updates.
    Mono<ProcessingContext> chain = Mono.just(ctx);
    Flux<ProcessingContext> emissions = Flux.empty();

    for (TextProcessor processor : buildOrderedChain()) {
      final TextProcessor stage = processor;
      chain = chain.flatMap(current -> {
        if (current.isCacheHit() && shouldBypassAfterCache(stage)) {
          return Mono.just(current.addStep(stage.name(), "bypass-cache"));
        }
        return stage.process(current);
      });

      // Emit the context produced by this stage before moving to the next one
      emissions = emissions.concatWith(chain);
    }

    return emissions;
  }

  private ProcessingContext initializeContext(PrepareRequest request) throws ValidationException {
    ValidationContext validationContext = validationService.validate(request.getQuery(), PromptRenderUtils.baseSystemPrompt());

    ProcessingContext ctx = new ProcessingContext()
        .setUserId(request.getUserId())
        .setSessionId(request.getSessionId())
        .setRawInput(validationContext.getProcessedInput())
        .setSystemPrompt(validationContext.getSystemPrompt())
        .setValidationNotices(new ArrayList<>(validationContext.getNotices()));

    if (ctx.getEntities() == null) ctx.setEntities(new LinkedHashMap<>());
    if (ctx.getOutline() == null) ctx.setOutline(new LinkedHashMap<>());

    return ctx;
  }

  private List<TextProcessor> buildOrderedChain() {
    Set<TextProcessor> seen = new LinkedHashSet<>();
    List<TextProcessor> ordered = new ArrayList<>();

    // 1) Add processors in DEFAULT_ORDER if present
    for (Class<? extends TextProcessor> type : DEFAULT_ORDER) {
      TextProcessor processor = processorsByType.get(type);
      if (processor != null && seen.add(processor)) {
        ordered.add(processor);
      }
    }

    // 2) Add any remaining processors (in registration order)
    for (TextProcessor processor : processorsByType.values()) {
      if (seen.add(processor)) {
        ordered.add(processor);
      }
    }

    return ordered;
  }

  @SuppressWarnings("unchecked")
  private static Class<? extends TextProcessor> getConcreteType(TextProcessor p) {
    Class<?> target = AopUtils.getTargetClass(p);
    // If AOP gives a proxy that still isn’t assignable (rare), fall back to runtime class
    if (target == null || !TextProcessor.class.isAssignableFrom(target)) {
      target = p.getClass();
    }
    return (Class<? extends TextProcessor>) target;
  }

  private boolean shouldBypassAfterCache(TextProcessor processor) {
    return processor instanceof PromptTemplateProcessor;
  }
}
