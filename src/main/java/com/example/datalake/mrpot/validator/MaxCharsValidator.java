package com.example.datalake.mrpot.validator;

import com.example.datalake.mrpot.model.ProcessingContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Hard guard on raw input size. Large payloads are truncated to a safe maximum to keep the
 * downstream processors responsive.
 */
@Component
public class MaxCharsValidator implements ProcessingValidator {

    private static final int MAX_CHARS = 4096;

    @Override
    public Stage stage() {
        return Stage.PRE_CHAIN;
    }

    @Override
    public String name() {
        return "validator:max-chars";
    }

    @Override
    public Mono<ProcessingContext> validate(ProcessingContext ctx) {
        String raw = ctx.getRawInput();
        if (raw == null) {
            ctx.setCharLimit(Math.min(ctx.getCharLimit(), MAX_CHARS));
            return Mono.just(ctx);
        }

        if (raw.length() > MAX_CHARS) {
            String truncated = raw.substring(0, MAX_CHARS);
            ctx.setRawInput(truncated);
            ctx.addStep(name(), "truncated:" + raw.length() + "->" + truncated.length());
        }

        if (ctx.getCharLimit() > MAX_CHARS) {
            ctx.setCharLimit(MAX_CHARS);
        }

        return Mono.just(ctx);
    }
}
