package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.PromptTemplate;
import com.example.datalake.mrpot.service.PromptTemplateCatalog;
import com.github.mustachejava.DefaultMustacheFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplateProcessorTest {

    @Test
    void shouldRenderTemplateAndKeepContextPlaceholder() {
        PromptTemplate template = new PromptTemplate();
        template.setId("general-en");
        template.setIntent("general");
        template.setLanguage("en");
        template.setSystem("You are MrPot. Tone={{tone}}.\n{{CONTEXT}}");
        template.setUserTemplate("User({{userId}}): {{input}}");
        template.setVars(Map.of("tone", "friendly"));
        template.setFewShot(List.of("Example question: {{normalized}}", "Answer outline: {{outline}}"));

        PromptTemplateCatalog catalog = PromptTemplateCatalog.of(List.of(template));
        PromptTemplateProcessor processor = new PromptTemplateProcessor(catalog, new DefaultMustacheFactory());

        ProcessingContext ctx = new ProcessingContext();
        ctx.setUserId("alice");
        ctx.setSessionId("sess-1");
        ctx.setRawInput("How many tables do we have?");
        ctx.setNormalized("How many tables do we have?");
        ctx.setLanguage(Language.builder().isoCode("en").displayName("English").build());
        ctx.setIntent(Intent.GENERAL);

        ProcessingContext out = processor.process(ctx).block();
        assertNotNull(out);
        assertNotNull(out.getTemplate());
        assertEquals("general-en", out.getTemplate().getId());
        assertTrue(out.getSystemPrompt().contains("Tone=friendly"));
        assertTrue(out.getSystemPrompt().contains("{{CONTEXT}}"));
        assertEquals("User(alice): How many tables do we have?", out.getUserPrompt());
        assertTrue(out.getFinalPrompt().contains(out.getSystemPrompt()));
        assertTrue(out.getFinalPrompt().contains(out.getUserPrompt()));
        assertTrue(out.getSteps().stream().anyMatch(step -> step.getName().equals("prompt-template")));
    }

    @Test
    void shouldFallbackWhenTemplateMissing() {
        PromptTemplateCatalog catalog = PromptTemplateCatalog.of(List.of());
        PromptTemplateProcessor processor = new PromptTemplateProcessor(catalog, new DefaultMustacheFactory());

        ProcessingContext ctx = new ProcessingContext();
        ctx.setUserId("bob");
        ctx.setRawInput("Hello");
        ctx.setIntent(Intent.UNKNOWN);

        ProcessingContext out = processor.process(ctx).block();
        assertNotNull(out);
        assertNull(out.getTemplate());
        assertEquals(PromptTemplateProcessor.DEFAULT_SYSTEM_PROMPT, out.getSystemPrompt());
        assertTrue(out.getSystemPrompt().contains("{{CONTEXT}}"));
        assertEquals("User(bob): Hello", out.getUserPrompt());
        assertEquals(out.getSystemPrompt() + "\n---\n" + out.getUserPrompt(), out.getFinalPrompt());
        assertTrue(out.getSteps().stream().anyMatch(step -> step.getNote().contains("using defaults")));
    }

    @Test
    void shouldFallbackToBaseLanguage() {
        PromptTemplate template = new PromptTemplate();
        template.setId("general-en-base");
        template.setIntent("general");
        template.setLanguage("en");
        template.setSystem("Base English template.\n{{CONTEXT}}");
        template.setUserTemplate("User({{userId}}): {{input}}");

        PromptTemplateCatalog catalog = PromptTemplateCatalog.of(List.of(template));
        PromptTemplateProcessor processor = new PromptTemplateProcessor(catalog, new DefaultMustacheFactory());

        ProcessingContext ctx = new ProcessingContext();
        ctx.setUserId("zoe");
        ctx.setRawInput("Hola");
        ctx.setLanguage(Language.builder().isoCode("en-US").displayName("English").build());
        ctx.setIntent(Intent.GENERAL);

        ProcessingContext out = processor.process(ctx).block();
        assertNotNull(out);
        assertNotNull(out.getTemplate());
        assertEquals("general-en-base", out.getTemplate().getId());
        assertEquals("Base English template.\n{{CONTEXT}}", out.getSystemPrompt());
    }
}

