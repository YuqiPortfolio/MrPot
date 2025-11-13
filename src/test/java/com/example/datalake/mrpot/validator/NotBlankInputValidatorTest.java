package com.example.datalake.mrpot.validator;

import com.example.datalake.mrpot.model.ProcessingContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotBlankInputValidatorTest {

    private final NotBlankInputValidator validator = new NotBlankInputValidator();

    @Test
    void replacesBlankInputWithFallback() {
        ProcessingContext ctx = new ProcessingContext().setRawInput("   ");

        ProcessingContext result = validator.validate(ctx).block();

        assertEquals(NotBlankInputValidator.FALLBACK_TEXT, result.getRawInput());
        assertFalse(result.getSteps().isEmpty());
        assertEquals(validator.name(), result.getSteps().get(result.getSteps().size() - 1).getName());
    }

    @Test
    void trimsLeadingAndTrailingWhitespace() {
        ProcessingContext ctx = new ProcessingContext().setRawInput("  hello world  ");

        ProcessingContext result = validator.validate(ctx).block();

        assertEquals("hello world", result.getRawInput());
        assertFalse(result.getSteps().isEmpty());
        assertEquals("trimmed-leading-trailing-space",
                result.getSteps().get(result.getSteps().size() - 1).getNote());
    }
}
