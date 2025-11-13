package com.example.datalake.mrpot.validator;

import com.example.datalake.mrpot.model.ProcessingContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaxCharsValidatorTest {

    private final MaxCharsValidator validator = new MaxCharsValidator();

    @Test
    void truncatesWhenExceedingLimit() {
        String longText = "a".repeat(5000);
        ProcessingContext ctx = new ProcessingContext().setRawInput(longText).setCharLimit(8000);

        ProcessingContext result = validator.validate(ctx).block();

        assertEquals(4096, result.getRawInput().length());
        assertEquals(4096, result.getCharLimit());
        assertFalse(result.getSteps().isEmpty());
        assertEquals(validator.name(), result.getSteps().get(result.getSteps().size() - 1).getName());
    }

    @Test
    void noChangeWhenWithinLimit() {
        ProcessingContext ctx = new ProcessingContext().setRawInput("short");

        ProcessingContext result = validator.validate(ctx).block();

        assertEquals("short", result.getRawInput());
        assertTrue(result.getSteps().isEmpty());
    }
}
