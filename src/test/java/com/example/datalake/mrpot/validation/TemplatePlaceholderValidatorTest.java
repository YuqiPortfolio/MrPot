package com.example.datalake.mrpot.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TemplatePlaceholderValidatorTest {

  private final TemplatePlaceholderValidator validator = new TemplatePlaceholderValidator();

  @Test
  void shouldInjectPlaceholderWhenMissing() {
    ValidationContext context = new ValidationContext("question", "System prompt");

    validator.validate(context);

    assertThat(context.getSystemPrompt()).contains("{{CONTEXT}}");
    assertThat(context.getNotices())
        .singleElement()
        .isEqualTo("Injected missing {{CONTEXT}} placeholder into system prompt.");
  }

  @Test
  void shouldLeavePromptUntouchedWhenPlaceholderPresent() {
    ValidationContext context = new ValidationContext("question", "System {{CONTEXT}} prompt");

    validator.validate(context);

    assertThat(context.getSystemPrompt()).isEqualTo("System {{CONTEXT}} prompt");
    assertThat(context.getNotices()).isEmpty();
  }

  @Test
  void shouldHandleNullPrompt() {
    ValidationContext context = new ValidationContext("question", null);

    validator.validate(context);

    assertThat(context.getSystemPrompt()).isEqualTo("{{CONTEXT}}");
  }
}
