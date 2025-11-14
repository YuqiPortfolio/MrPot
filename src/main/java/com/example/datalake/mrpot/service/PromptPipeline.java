// src/main/java/com/example/datalake/mrpot/service/PromptPipeline.java
package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.processor.TextProcessor;
import com.example.datalake.mrpot.processor.UnifiedCleanCorrectProcessor;
import com.example.datalake.mrpot.processor.IntentClassifierProcessor;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.validation.ValidationContext;
import com.example.datalake.mrpot.validation.ValidationException;
import com.example.datalake.mrpot.validation.ValidationService;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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
//            LanguageTranslateProcessor.class,
      IntentClassifierProcessor.class
  );

  private static final String BASE_SYSTEM_PROMPT = """
          You are MrPot, a helpful data-lake assistant. Keep answers concise.
          """.trim();

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
    ValidationContext validationContext;
    try {
      validationContext = validationService.validate(request.getQuery(), BASE_SYSTEM_PROMPT);
    } catch (ValidationException ex) {
      return Mono.error(ex);
    }

    ProcessingContext ctx = new ProcessingContext()
        .setUserId(request.getUserId())
        .setSessionId(request.getSessionId())
        .setRawInput(validationContext.getProcessedInput())
        .setSystemPrompt(validationContext.getSystemPrompt())
        .setValidationNotices(new ArrayList<>(validationContext.getNotices()));

    if (ctx.getEntities() == null) ctx.setEntities(new LinkedHashMap<>());
    if (ctx.getOutline() == null) ctx.setOutline(new LinkedHashMap<>());

    Mono<ProcessingContext> pipeline = Mono.just(ctx);
    for (TextProcessor processor : buildOrderedChain()) {
      pipeline = pipeline.flatMap(processor::process);
    }
    return pipeline;
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
}
