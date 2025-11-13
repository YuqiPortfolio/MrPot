package com.example.datalake.mrpot.validator;

import com.example.datalake.mrpot.model.ProcessingContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Ensures the raw user input is present. If the request body is empty or consists only of
 * whitespace, we replace it with a friendly fallback so the downstream processors can still run.
 */
@Component
public class NotBlankInputValidator implements ProcessingValidator {

    static final String FALLBACK_TEXT = "[No user input provided]";

    @Override
    public Stage stage() {
        return Stage.PRE_CHAIN;
    }

    @Override
    public String name() {
        return "validator:not-blank-input";
    }

    @Override
    public Mono<ProcessingContext> validate(ProcessingContext ctx) {
        String raw = ctx.getRawInput();
        String trimmed = raw == null ? "" : raw.trim();

        if (trimmed.isEmpty()) {
            ctx.setRawInput(FALLBACK_TEXT);
            ctx.addStep(name(), "fallback-applied");
            return Mono.just(ctx);
        }

        if (!trimmed.equals(raw)) {
            ctx.setRawInput(trimmed);
            ctx.addStep(name(), "trimmed-leading-trailing-space");
        }

        return Mono.just(ctx);
    }
}
