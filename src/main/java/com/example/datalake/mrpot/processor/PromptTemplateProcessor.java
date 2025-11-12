package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.PromptTemplate;
import com.example.datalake.mrpot.service.PromptTemplateCatalog;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PromptTemplateProcessor implements TextProcessor {

    public static final String DEFAULT_SYSTEM_PROMPT = "You are MrPot, a helpful data-lake assistant. Keep answers concise.\n\n{{CONTEXT}}";
    public static final String DEFAULT_USER_TEMPLATE = "User({{userId}}): {{input}}";
    private static final String NAME = "prompt-template";

    private final PromptTemplateCatalog catalog;
    private final MustacheFactory mustacheFactory;

    public PromptTemplateProcessor(PromptTemplateCatalog catalog) {
        this(catalog, new DefaultMustacheFactory());
    }

    PromptTemplateProcessor(PromptTemplateCatalog catalog, MustacheFactory mustacheFactory) {
        this.catalog = catalog;
        this.mustacheFactory = mustacheFactory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Mono<ProcessingContext> process(ProcessingContext ctx) {
        String language = resolveLanguage(ctx);
        String intent = resolveIntent(ctx);

        Optional<PromptTemplate> templateOpt = catalog.find(intent, language);

        if (templateOpt.isEmpty()) {
            applyDefaults(ctx, language, intent);
            return Mono.just(ctx);
        }

        PromptTemplate template = templateOpt.get();
        ctx.setTemplate(template);

        Map<String, Object> model = buildModel(ctx, template, language, intent);

        String systemPrompt = renderTemplate(template.getSystem(), model, template.getId() + "::system");
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }
        if (!systemPrompt.contains("{{CONTEXT}}")) {
            systemPrompt = systemPrompt.stripTrailing() + "\n\n{{CONTEXT}}";
        }

        String userPrompt = renderTemplate(template.getUserTemplate(), model, template.getId() + "::user");
        if (userPrompt == null || userPrompt.isBlank()) {
            userPrompt = defaultUserPrompt(ctx);
        }

        List<String> renderedFewShot = Optional.ofNullable(template.getFewShot())
                .orElseGet(List::of)
                .stream()
                .map(item -> renderTemplate(item, model, template.getId() + "::few-shot"))
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        String finalPrompt = assembleFinalPrompt(systemPrompt, renderedFewShot, userPrompt);

        ctx.setSystemPrompt(systemPrompt);
        ctx.setUserPrompt(userPrompt);
        ctx.setFinalPrompt(finalPrompt);

        ctx.addStep(NAME, String.format(Locale.ROOT,
                "template=%s, intent=%s, lang=%s", template.getId(), intent, language));
        return Mono.just(ctx);
    }

    private void applyDefaults(ProcessingContext ctx, String language, String intent) {
        String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        String userPrompt = defaultUserPrompt(ctx);
        String finalPrompt = assembleFinalPrompt(systemPrompt, List.of(), userPrompt);

        ctx.setSystemPrompt(systemPrompt);
        ctx.setUserPrompt(userPrompt);
        ctx.setFinalPrompt(finalPrompt);
        ctx.addStep(NAME, String.format(Locale.ROOT,
                "no-template intent=%s, lang=%s; using defaults", intent, language));
    }

    private String assembleFinalPrompt(String systemPrompt, List<String> fewShot, String userPrompt) {
        List<String> segments = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            segments.add(systemPrompt.strip());
        }
        if (fewShot != null && !fewShot.isEmpty()) {
            segments.add(String.join("\n\n", fewShot));
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            segments.add(userPrompt.strip());
        }
        if (segments.isEmpty()) {
            return null;
        }
        return String.join("\n---\n", segments);
    }

    private Map<String, Object> buildModel(ProcessingContext ctx, PromptTemplate template, String language, String intent) {
        Map<String, Object> model = new LinkedHashMap<>();
        String normalized = ctx.getNormalized();
        String input = (normalized == null || normalized.isBlank()) ? ctx.getRawInput() : normalized;

        model.put("input", input);
        model.put("rawInput", ctx.getRawInput());
        model.put("normalized", normalized);
        model.put("corrected", ctx.getCorrected());
        model.put("userId", ctx.getUserId() == null ? "anonymous" : ctx.getUserId());
        model.put("sessionId", ctx.getSessionId());
        model.put("intent", intent);
        model.put("intentName", ctx.getIntent() == null ? null : ctx.getIntent().name());
        model.put("language", language);
        Language detected = ctx.getLanguage();
        if (detected != null) {
            model.put("languageDisplay", detected.getDisplayName());
            model.put("languageIso", detected.getIsoCode());
        }
        model.put("tags", ctx.getTags());
        model.put("entities", ctx.getEntities());
        model.put("outline", ctx.getOutline());
        model.put("charLimit", ctx.getCharLimit());
        model.put("now", ctx.getNow());
        model.put("timestamp", ctx.getNow() == null ? null : DateTimeFormatter.ISO_INSTANT.format(ctx.getNow()));
        model.put("CONTEXT", "{{CONTEXT}}");
        model.put("templateId", template.getId());

        Map<String, String> vars = template.getVars();
        if (vars != null) {
            vars.forEach(model::putIfAbsent);
        }

        return model;
    }

    private String renderTemplate(String template, Map<String, Object> model, String name) {
        if (template == null || template.isBlank()) {
            return null;
        }
        try {
            Mustache mustache = mustacheFactory.compile(new StringReader(template), name);
            StringWriter writer = new StringWriter();
            mustache.execute(writer, model).flush();
            return writer.toString().strip();
        } catch (Exception ex) {
            log.warn("Failed to render template {}: {}", name, ex.getMessage());
            return template;
        }
    }

    private String resolveIntent(ProcessingContext ctx) {
        Intent intent = ctx.getIntent();
        if (intent == null) {
            return "unknown";
        }
        return intent.name().toLowerCase(Locale.ROOT);
    }

    private String resolveLanguage(ProcessingContext ctx) {
        Language language = ctx.getLanguage();
        if (language != null && language.getIsoCode() != null && !language.getIsoCode().isBlank()) {
            return language.getIsoCode().toLowerCase(Locale.ROOT);
        }
        String indexLang = ctx.getIndexLanguage();
        if (indexLang != null && !indexLang.isBlank()) {
            return indexLang.toLowerCase(Locale.ROOT);
        }
        return "en";
    }

    private String defaultUserPrompt(ProcessingContext ctx) {
        String userId = ctx.getUserId() == null || ctx.getUserId().isBlank() ? "anonymous" : ctx.getUserId();
        String normalized = ctx.getNormalized();
        String input = (normalized == null || normalized.isBlank()) ? ctx.getRawInput() : normalized;
        Map<String, Object> model = Map.of(
                "userId", userId,
                "input", input == null ? "" : input
        );
        return renderTemplate(DEFAULT_USER_TEMPLATE, model, "default-user");
    }
}

