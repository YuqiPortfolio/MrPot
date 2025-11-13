// src/main/java/com/example/datalake/mrpot/service/PromptPipeline.java
package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.processor.TextProcessor;
import com.example.datalake.mrpot.processor.UnifiedCleanCorrectProcessor;
import com.example.datalake.mrpot.processor.IntentClassifierProcessor;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.validator.ProcessingValidator;
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

    private final Map<Class<? extends TextProcessor>, TextProcessor> processorsByType;
    private final Map<ProcessingValidator.Stage, List<ProcessingValidator>> validatorsByStage;

    public PromptPipeline(List<TextProcessor> processors, List<ProcessingValidator> validators) {
        // Use AopUtils.getTargetClass to handle Spring proxies (CGLIB/JDK)
        this.processorsByType = processors.stream()
                .collect(Collectors.toMap(
                        p -> getConcreteType(p),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<ProcessingValidator> safeValidators = validators == null ? List.of() : validators;
        this.validatorsByStage = safeValidators.stream()
                .collect(Collectors.groupingBy(
                        ProcessingValidator::stage,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)
                ));
    }

    public Mono<ProcessingContext> run(PrepareRequest request) {
        ProcessingContext ctx = new ProcessingContext()
                .setUserId(request.getUserId())
                .setSessionId(request.getSessionId())
                .setRawInput(request.getQuery());

        if (ctx.getEntities() == null) ctx.setEntities(new LinkedHashMap<>());
        if (ctx.getOutline() == null) ctx.setOutline(new LinkedHashMap<>());

        Mono<ProcessingContext> pipeline = Mono.just(ctx)
                .flatMap(c -> applyValidators(c, ProcessingValidator.Stage.PRE_CHAIN));
        for (TextProcessor processor : buildOrderedChain()) {
            pipeline = pipeline.flatMap(processor::process);
        }
        return pipeline.flatMap(c -> applyValidators(c, ProcessingValidator.Stage.PRE_LLM));
    }

    private Mono<ProcessingContext> applyValidators(ProcessingContext ctx, ProcessingValidator.Stage stage) {
        List<ProcessingValidator> validators = validatorsByStage.get(stage);
        if (validators == null || validators.isEmpty()) {
            return Mono.just(ctx);
        }

        Mono<ProcessingContext> pipeline = Mono.just(ctx);
        for (ProcessingValidator validator : validators) {
            pipeline = pipeline.flatMap(validator::validate);
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
