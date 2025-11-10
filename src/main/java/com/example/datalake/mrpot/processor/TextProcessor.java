package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.ProcessingContext;
import reactor.core.publisher.Mono;

public interface TextProcessor {
    String name();
    Mono<ProcessingContext> process(ProcessingContext ctx);
}
