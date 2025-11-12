package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.PromptTemplate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateProcessorTest {

    @Test
    void fillsTemplateAndEnrichesPrompts() {
        PromptTemplate template = new PromptTemplate();
        template.setId("login-en");
        template.setIntent("login");
        template.setLanguage("en");
        template.setSystem("Assist with {{intent}} issues.");
        template.setUserTemplate("User({{userId}}) says: {{input}} (lang={{language}})");
        template.setVars(Map.of("supportEmail", "support@example.com"));
        template.setFewShot(List.of("Example for {{intent}} using {{keyword_1}}", "Contact {{supportEmail}} if blocked."));

        PromptTemplateProcessor processor = new PromptTemplateProcessor(List.of(template));

        ProcessingContext ctx = new ProcessingContext();
        ctx.setRawInput("Please help me sign in to the data lake platform");
        ctx.setNormalized("Sign in to the data lake platform account");
        ctx.setLanguage(new Language("en", "English", 0.99, null));
        ctx.setIntent(Intent.LOGIN);
        ctx.setUserId("alice");
        ctx.setSessionId("sess-1");
        ctx.setNow(Instant.parse("2024-01-01T00:00:00Z"));
        ctx.setEntities(new LinkedHashMap<>(Map.of("product", List.of("Data Lake Platform"))));
        ctx.setTags(new LinkedHashSet<>(List.of("auth", "intent:login", "critical")));

        ProcessingContext result = processor.process(ctx).block();
        assertThat(result).isNotNull();
        assertThat(result.getTemplate()).isSameAs(template);

        assertThat(result.getSystemPrompt())
                .contains("Assist with login issues.")
                .contains("Top keywords: auth, critical, data lake platform, account, data, lake, platform, sign");

        assertThat(result.getUserPrompt())
                .isEqualTo("User(alice) says: Sign in to the data lake platform account (lang=en)");

        assertThat(result.getFinalPrompt())
                .contains("Examples:")
                .contains("- Example for login using auth")
                .contains("- Contact support@example.com if blocked.")
                .contains("---\nUser(alice) says: Sign in to the data lake platform account (lang=en)");

        assertThat(result.getSteps()).isNotEmpty();
        assertThat(result.getSteps().get(result.getSteps().size() - 1).getName()).isEqualTo("prompt-template");
    }
}
