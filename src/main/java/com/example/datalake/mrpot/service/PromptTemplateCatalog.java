package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.PromptTemplate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Simple in-memory catalog for prompt templates loaded from JSON resources.
 */
@Slf4j
@Component
public class PromptTemplateCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Map<String, PromptTemplate> templatesByKey;

    public PromptTemplateCatalog(ResourceLoader resourceLoader) {
        this(loadTemplates(resourceLoader));
    }

    private PromptTemplateCatalog(Collection<PromptTemplate> templates) {
        Map<String, PromptTemplate> map = new LinkedHashMap<>();
        for (PromptTemplate tpl : templates) {
            if (tpl == null) {
                continue;
            }
            String intent = normalize(tpl.getIntent(), "default");
            String language = normalize(tpl.getLanguage(), "default");
            map.put(key(intent, language), tpl);
        }
        this.templatesByKey = Collections.unmodifiableMap(map);
    }

    public static PromptTemplateCatalog of(Collection<PromptTemplate> templates) {
        return new PromptTemplateCatalog(templates);
    }

    public Optional<PromptTemplate> find(String intent, String language) {
        if (templatesByKey.isEmpty()) {
            return Optional.empty();
        }

        List<String> intentKeys = new ArrayList<>();
        if (intent != null && !intent.isBlank()) {
            String normalizedIntent = intent.toLowerCase(Locale.ROOT);
            intentKeys.add(normalizedIntent);
            intentKeys.add(normalizedIntent.replace('-', '_'));
        }
        intentKeys.add("default");
        intentKeys.add("*");

        List<String> languageKeys = new ArrayList<>();
        if (language != null && !language.isBlank()) {
            String normalizedLang = language.toLowerCase(Locale.ROOT);
            languageKeys.add(normalizedLang);
            int dashIdx = normalizedLang.indexOf('-');
            if (dashIdx > 0) {
                languageKeys.add(normalizedLang.substring(0, dashIdx));
            }
        }
        languageKeys.add("default");
        languageKeys.add("*");

        for (String intentKey : intentKeys) {
            for (String langKey : languageKeys) {
                PromptTemplate tpl = templatesByKey.get(key(intentKey, langKey));
                if (tpl != null) {
                    return Optional.of(tpl);
                }
            }
        }
        return Optional.empty();
    }

    private static Collection<PromptTemplate> loadTemplates(ResourceLoader resourceLoader) {
        List<String> candidates = new ArrayList<>();
        String sysProp = System.getProperty("prompt.templates");
        if (sysProp != null && !sysProp.isBlank()) {
            candidates.add(sysProp);
        }
        String envProp = System.getenv("PROMPT_TEMPLATES_PATH");
        if (envProp != null && !envProp.isBlank()) {
            candidates.add(envProp);
        }
        candidates.add("classpath:prompt_templates.json");

        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try (InputStream in = open(resourceLoader, candidate)) {
                if (in == null) {
                    continue;
                }
                TemplateBundle bundle = MAPPER.readValue(in, TemplateBundle.class);
                if (bundle.templates != null && !bundle.templates.isEmpty()) {
                    log.info("Loaded {} prompt templates from {}", bundle.templates.size(), candidate);
                    return bundle.templates;
                }
            } catch (IOException ex) {
                log.warn("Failed to load prompt templates from {}: {}", candidate, ex.getMessage());
            }
        }

        PromptTemplate fallback = new PromptTemplate();
        fallback.setId("default-fallback");
        fallback.setIntent("default");
        fallback.setLanguage("default");
        fallback.setSystem("You are MrPot, a helpful data-lake assistant. Keep answers concise.\n\n{{CONTEXT}}");
        fallback.setUserTemplate("User({{userId}}): {{input}}");
        log.info("Prompt template catalog initialized with built-in fallback template");
        return List.of(fallback);
    }

    private static InputStream open(ResourceLoader loader, String location) throws IOException {
        if (location == null || location.isBlank()) {
            return null;
        }

        if (location.startsWith("classpath:")) {
            Resource resource = loader.getResource(location);
            return resource.exists() ? resource.getInputStream() : null;
        }

        File file = new File(location);
        if (file.exists() && file.isFile()) {
            return new FileInputStream(file);
        }

        Resource resource = loader.getResource(location);
        return resource.exists() ? resource.getInputStream() : null;
    }

    private static String normalize(String value, String fallback) {
        return (value == null || value.isBlank())
                ? fallback
                : value.toLowerCase(Locale.ROOT);
    }

    private static String key(String intent, String language) {
        return normalize(intent, "default") + "#" + normalize(language, "default");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TemplateBundle {
        public List<PromptTemplate> templates;
    }
}

