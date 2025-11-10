package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.processor.LanguageDetectorProcessor;
import com.example.datalake.mrpot.processor.TextProcessor;
import com.example.datalake.mrpot.processor.UnifiedCleanCorrectProcessor;
import com.example.datalake.mrpot.request.PrepareRequest;
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

    private static final List<Class<? extends TextProcessor>> DEFAULT_ORDER = List.of(
            UnifiedCleanCorrectProcessor.class,
            LanguageDetectorProcessor.class
    );

    private final Map<Class<? extends TextProcessor>, TextProcessor> processorsByType;

    public PromptPipeline(List<TextProcessor> processors) {
        this.processorsByType = processors.stream()
                .collect(Collectors.toMap(TextProcessor::getClass, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    public Mono<ProcessingContext> run(PrepareRequest request) {
        ProcessingContext ctx = new ProcessingContext()
                .setUserId(request.getUserId())
                .setSessionId(request.getSessionId())
                .setRawInput(request.getQuery());

        if (ctx.getEntities() == null) {
            ctx.setEntities(new LinkedHashMap<>());
        }
        if (ctx.getOutline() == null) {
            ctx.setOutline(new LinkedHashMap<>());
        }

        Mono<ProcessingContext> pipeline = Mono.just(ctx);
        for (TextProcessor processor : buildOrderedChain()) {
            pipeline = pipeline.flatMap(processor::process);
        }

        return pipeline;
    }

    private List<TextProcessor> buildOrderedChain() {
        Set<TextProcessor> seen = new LinkedHashSet<>();
        List<TextProcessor> ordered = new ArrayList<>();

        for (Class<? extends TextProcessor> type : DEFAULT_ORDER) {
            TextProcessor processor = processorsByType.get(type);
            if (processor != null && seen.add(processor)) {
                ordered.add(processor);
            }
        }

        for (TextProcessor processor : processorsByType.values()) {
            if (seen.add(processor)) {
                ordered.add(processor);
            }
        }

        return ordered;
    }
}
