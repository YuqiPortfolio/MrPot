package com.example.datalake.assistant.prompt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplateRegistry {

    private final Map<String, Map<String, TemplateDefinition>> templates;

    public PromptTemplateRegistry(ObjectMapper objectMapper) throws IOException {
        Map<String, Map<String, TemplateDefinition>> loaded;
        try (InputStream inputStream = new ClassPathResource("prompt_templates.json").getInputStream()) {
            loaded = objectMapper.readValue(
                    inputStream, new TypeReference<Map<String, Map<String, TemplateDefinition>>>() {});
        }
        this.templates = loaded != null ? loaded : Collections.emptyMap();
    }

    public TemplateDefinition resolveTemplate(String language, String intent) {
        if (templates.isEmpty()) {
            return TemplateDefinition.defaultTemplate();
        }
        Map<String, TemplateDefinition> intents =
                templates.getOrDefault(language, templates.getOrDefault("en", Collections.emptyMap()));
        TemplateDefinition definition = intents.get(intent);
        if (definition == null) {
            definition = intents.getOrDefault("general", TemplateDefinition.defaultTemplate());
        }
        return definition;
    }

    public record TemplateDefinition(String system, String user) {
        public static TemplateDefinition defaultTemplate() {
            return new TemplateDefinition("You are a helpful assistant.", "Question: {{query}}\nAnswer succinctly.");
        }
    }
}
