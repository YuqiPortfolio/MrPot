package com.example.datalake.mrpot.validator;

import com.example.datalake.mrpot.model.ProcessingContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemplatePlaceholderValidatorTest {

    private final TemplatePlaceholderValidator validator = new TemplatePlaceholderValidator();

    @Test
    void appendsPlaceholderWhenMissing() {
        ProcessingContext ctx = new ProcessingContext().setSystemPrompt("You are helpful");

        ProcessingContext result = validator.validate(ctx).block();

        assertTrue(result.getSystemPrompt().contains("{{CONTEXT}}"));
        assertTrue(result.getSystemPrompt().startsWith("You are helpful"));
        assertFalse(result.getSteps().isEmpty());
        assertEquals(validator.name(), result.getSteps().get(result.getSteps().size() - 1).getName());
    }

    @Test
    void unchangedWhenPlaceholderPresent() {
        String prompt = "Use data\n{{CONTEXT}}";
        ProcessingContext ctx = new ProcessingContext().setSystemPrompt(prompt);

        ProcessingContext result = validator.validate(ctx).block();

        assertEquals(prompt, result.getSystemPrompt());
        assertTrue(result.getSteps().isEmpty());
    }
}
