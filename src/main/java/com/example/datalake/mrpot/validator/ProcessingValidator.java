package com.example.datalake.mrpot.validator;

import com.example.datalake.mrpot.model.ProcessingContext;
import reactor.core.publisher.Mono;

/**
 * Reactive validator executed at different checkpoints of the prompt pipeline.
 */
public interface ProcessingValidator {

    enum Stage {
        PRE_CHAIN,
        PRE_LLM
    }

    Stage stage();

    String name();

    Mono<ProcessingContext> validate(ProcessingContext ctx);
}
