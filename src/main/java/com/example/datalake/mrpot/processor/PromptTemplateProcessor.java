package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.PromptTemplate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Post processing step that enriches the context using an intent/language specific prompt template.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Select template using {@code intent × language}</li>
 *     <li>Populate template variables (user/session metadata, detected entities, keywords, ...)</li>
 *     <li>Rewrite the system prompt while preserving the template content and appending keyword hints</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptTemplateProcessor implements TextProcessor {

    private static final String NAME = "prompt-template";
    private static final String DEFAULT_SYSTEM_PROMPT = "You are MrPot, a assistant focused on internal knowledge bases.";
    private static final String DEFAULT_USER_TEMPLATE = "User({{userId}}): {{input}}";
    private static final int MAX_KEYWORDS = 8;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}", Pattern.MULTILINE);
    private static final Pattern WORD_TOKEN = Pattern.compile("[\\p{IsAlphabetic}0-9]{3,}");

    private final TemplateStore templateStore;

    public PromptTemplateProcessor(ResourceLoader resourceLoader) {
        this(TemplateStore.fromResource(resourceLoader, "prompt_templates.json"));
    }

    PromptTemplateProcessor(Collection<PromptTemplate> templates) {
        this(TemplateStore.fromCollection(templates));
    }

    private PromptTemplateProcessor(TemplateStore store) {
        this.templateStore = store;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Mono<ProcessingContext> process(ProcessingContext ctx) {
        PromptTemplate template = Optional.ofNullable(ctx.getTemplate())
                .orElseGet(() -> templateStore
                        .findTopByIntentAndLanguage(resolveIntent(ctx), resolveLanguage(ctx))
                        .orElse(null));

        if (template != null) {
            ctx.setTemplate(template);
        }

        List<String> keywords = collectKeywords(ctx);
        Map<String, String> vars = buildVariableMap(ctx, template, keywords);

        String systemBase = template != null && notBlank(template.getSystem())
                ? template.getSystem()
                : DEFAULT_SYSTEM_PROMPT;
        String systemPrompt = enrichSystemPrompt(render(systemBase, vars), keywords);
        ctx.setSystemPrompt(systemPrompt);

        String userTemplate = template != null && notBlank(template.getUserTemplate())
                ? template.getUserTemplate()
                : DEFAULT_USER_TEMPLATE;
        String userPrompt = render(userTemplate, vars);
        ctx.setUserPrompt(userPrompt);

        String finalPrompt = buildFinalPrompt(systemPrompt, template, vars, userPrompt);
        ctx.setFinalPrompt(finalPrompt);

        ctx.addStep(NAME, "template=" + (template != null ? template.getId() : "default")
                + ", keywords=" + keywords);

        return Mono.just(ctx);
    }

    private String resolveIntent(ProcessingContext ctx) {
        Intent intent = ctx.getIntent();
        if (intent == null) {
            return "unknown";
        }
        return intent.name().toLowerCase(Locale.ROOT);
    }

    private String resolveLanguage(ProcessingContext ctx) {
        Language lang = ctx.getLanguage();
        if (lang != null && notBlank(lang.getIsoCode())) {
            return lang.getIsoCode().toLowerCase(Locale.ROOT);
        }
        if (notBlank(ctx.getIndexLanguage())) {
            return ctx.getIndexLanguage().toLowerCase(Locale.ROOT);
        }
        return "en";
    }

    private Map<String, String> buildVariableMap(ProcessingContext ctx,
                                                 PromptTemplate template,
                                                 List<String> keywords) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("input", Optional.ofNullable(ctx.getNormalized()).filter(this::notBlank).orElse(ctx.getRawInput()));
        vars.put("normalized", defaultString(ctx.getNormalized()));
        vars.put("raw", defaultString(ctx.getRawInput()));
        vars.put("userId", defaultString(ctx.getUserId(), "anonymous"));
        vars.put("sessionId", defaultString(ctx.getSessionId()));
        vars.put("intent", resolveIntent(ctx));
        vars.put("language", resolveLanguage(ctx));
        vars.put("charLimit", String.valueOf(ctx.getCharLimit()));
        vars.put("now", Optional.ofNullable(ctx.getNow()).map(Object::toString).orElse(""));

        if (ctx.getEntities() != null) {
            ctx.getEntities().forEach((key, values) -> {
                String joined = join(values);
                vars.put("entity." + key, joined);
                vars.put("entity_" + key, joined);
            });
        }

        if (template != null && template.getVars() != null) {
            template.getVars().forEach((k, v) -> {
                if (k != null && v != null && notBlank(k)) {
                    vars.putIfAbsent(k.trim(), v);
                }
            });
        }

        if (!keywords.isEmpty()) {
            vars.put("keywords", String.join(", ", keywords));
            for (int i = 0; i < keywords.size(); i++) {
                vars.put("keyword_" + (i + 1), keywords.get(i));
            }
        }

        return vars;
    }

    private List<String> collectKeywords(ProcessingContext ctx) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();

        if (ctx.getTags() != null) {
            for (String tag : ctx.getTags()) {
                if (tag == null) continue;
                String cleaned = tag.trim();
                if (cleaned.isEmpty()) continue;
                if (cleaned.toLowerCase(Locale.ROOT).startsWith("intent:")) {
                    continue;
                }
                keywords.add(normalizeKeyword(cleaned));
            }
        }

        if (ctx.getEntities() != null) {
            ctx.getEntities().values().stream()
                    .filter(Objects::nonNull)
                    .forEach(list -> list.stream()
                            .filter(Objects::nonNull)
                            .map(this::normalizeKeyword)
                            .forEach(keywords::add));
        }

        List<String> textKeywords = extractTopTokens(ctx.getNormalized());
        for (String token : textKeywords) {
            keywords.add(token);
        }

        return keywords.stream().limit(MAX_KEYWORDS).toList();
    }

    private List<String> extractTopTokens(String text) {
        if (!notBlank(text)) {
            return List.of();
        }
        Map<String, Integer> freq = new LinkedHashMap<>();
        Matcher matcher = WORD_TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() < 3 || STOP_WORDS.contains(word)) {
                continue;
            }
            freq.merge(word, 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = Integer.compare(b.getValue(), a.getValue());
                    if (cmp != 0) return cmp;
                    return a.getKey().compareTo(b.getKey());
                })
                .map(Map.Entry::getKey)
                .limit(MAX_KEYWORDS)
                .collect(Collectors.toList());
    }

    private String buildFinalPrompt(String systemPrompt,
                                    PromptTemplate template,
                                    Map<String, String> vars,
                                    String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt.trim());

        List<String> fewShots = template != null ? template.getFewShot() : null;
        if (fewShots != null && !fewShots.isEmpty()) {
            sb.append("\n\nExamples:\n");
            for (String shot : fewShots) {
                if (shot == null || shot.isBlank()) continue;
                String rendered = render(shot, vars).trim();
                if (!rendered.isEmpty()) {
                    sb.append("- ").append(rendered).append("\n");
                }
            }
        }

        sb.append("\n---\n");
        sb.append(userPrompt.trim());
        return sb.toString();
    }

    private String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String replacement = vars.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String enrichSystemPrompt(String base, List<String> keywords) {
        if (keywords.isEmpty()) {
            return base.trim();
        }
        StringBuilder sb = new StringBuilder(base.trim());
        sb.append("\n\nTop keywords: ");
        sb.append(String.join(", ", keywords));
        return sb.toString();
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));
    }

    private String normalizeKeyword(String keyword) {
        String normalized = keyword.trim().replace('_', ' ');
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "into", "have", "your",
            "about", "after", "before", "when", "will", "been", "are", "was", "were",
            "which", "while", "where", "what", "why", "how", "use", "using", "into",
            "want", "need", "please", "help", "make", "sure", "more", "than"
    );

    // ---------------- Template store ----------------

    private static class TemplateStore {
        private final Map<String, PromptTemplate> byKey;

        private TemplateStore(Map<String, PromptTemplate> byKey) {
            this.byKey = byKey;
        }

        static TemplateStore fromCollection(Collection<PromptTemplate> templates) {
            Map<String, PromptTemplate> map = new LinkedHashMap<>();
            if (templates != null) {
                for (PromptTemplate t : templates) {
                    if (t == null) continue;
                    String key = key(t.getIntent(), t.getLanguage());
                    if (!key.isEmpty()) {
                        map.putIfAbsent(key, t);
                    }
                }
            }
            return new TemplateStore(map);
        }

        static TemplateStore fromResource(ResourceLoader resourceLoader, String classpathName) {
            Map<String, PromptTemplate> map = new LinkedHashMap<>();
            if (resourceLoader == null) {
                return new TemplateStore(map);
            }
            ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            try (InputStream in = open(resourceLoader, classpathName)) {
                if (in != null) {
                    TemplateBundle bundle = mapper.readValue(in, TemplateBundle.class);
                    if (bundle.templates != null) {
                        for (PromptTemplate t : bundle.templates) {
                            if (t == null) continue;
                            String key = key(t.getIntent(), t.getLanguage());
                            if (!key.isEmpty()) {
                                map.putIfAbsent(key, t);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load prompt templates: {}", e.toString());
            }
            return new TemplateStore(map);
        }

        Optional<PromptTemplate> findTopByIntentAndLanguage(String intent, String language) {
            String primary = key(intent, language);
            if (!primary.isEmpty()) {
                PromptTemplate tpl = byKey.get(primary);
                if (tpl != null) {
                    return Optional.of(tpl);
                }
            }
            if (!intent.isBlank()) {
                PromptTemplate tpl = byKey.get(key(intent, "en"));
                if (tpl != null) {
                    return Optional.of(tpl);
                }
            }
            PromptTemplate tpl = byKey.get(key("unknown", language));
            if (tpl != null) {
                return Optional.of(tpl);
            }
            return Optional.ofNullable(byKey.get(key("unknown", "en")));
        }

        private static String key(String intent, String language) {
            if (intent == null || intent.isBlank()) {
                return "";
            }
            if (language == null || language.isBlank()) {
                return intent.trim().toLowerCase(Locale.ROOT) + "#en";
            }
            return intent.trim().toLowerCase(Locale.ROOT) + "#" + language.trim().toLowerCase(Locale.ROOT);
        }

        private static InputStream open(ResourceLoader resourceLoader, String classpathName) {
            try {
                Resource r = resourceLoader.getResource("classpath:" + classpathName);
                if (r != null && r.exists()) {
                    return r.getInputStream();
                }
            } catch (Exception ignore) { }

            try {
                InputStream in = PromptTemplateProcessor.class.getClassLoader().getResourceAsStream(classpathName);
                if (in != null) {
                    return in;
                }
            } catch (Exception ignore) { }

            try {
                String external = Optional.ofNullable(System.getProperty("PROMPT_TEMPLATES_PATH"))
                        .orElse(System.getenv("PROMPT_TEMPLATES_PATH"));
                if (external != null && !external.isBlank()) {
                    File file = new File(external);
                    if (file.exists() && file.isFile()) {
                        return new FileInputStream(file);
                    }
                }
            } catch (Exception ignore) { }

            try {
                File file = new File("src/main/resources/" + classpathName);
                if (file.exists() && file.isFile()) {
                    return new FileInputStream(file);
                }
            } catch (Exception ignore) { }

            return null;
        }
    }

    @Data
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TemplateBundle {
        private List<PromptTemplate> templates;
    }
}
