package com.example.datalake.mrpot.validator;

import com.example.datalake.mrpot.model.ProcessingContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * Ensures the system prompt contains the {{CONTEXT}} placeholder required by our downstream LLM
 * templates. If it is missing we append a minimal instruction with the placeholder.
 */
@Component
public class TemplatePlaceholderValidator implements ProcessingValidator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*CONTEXT\\s*}}", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_HEADER = "Use the following context when answering:";

    @Override
    public Stage stage() {
        return Stage.PRE_LLM;
    }

    @Override
    public String name() {
        return "validator:template-placeholder";
    }

    @Override
    public Mono<ProcessingContext> validate(ProcessingContext ctx) {
        String system = ctx.getSystemPrompt();
        if (system == null) system = "";

        if (PLACEHOLDER.matcher(system).find()) {
            return Mono.just(ctx);
        }

        String trimmed = system.trim();
        StringBuilder sb = new StringBuilder();
        if (!trimmed.isEmpty()) {
            sb.append(trimmed);
            if (!trimmed.endsWith("\n")) {
                sb.append("\n\n");
            } else {
                sb.append('\n');
            }
        }
        sb.append(DEFAULT_HEADER).append("\n{{CONTEXT}}");

        ctx.setSystemPrompt(sb.toString());
        ctx.addStep(name(), "placeholder-injected");
        return Mono.just(ctx);
    }
}
