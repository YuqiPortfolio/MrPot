package com.example.datalake.mrpot.validation;

import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Guarantees that the system prompt contains the {{CONTEXT}} placeholder by injecting it when
 * absent.
 */
@Component
public class TemplatePlaceholderValidator implements Validator {

  private static final String CONTEXT_PLACEHOLDER = "{{CONTEXT}}";

  @Override
  public ValidationStage stage() {
    return ValidationStage.PRE_LLM;
  }

  @Override
  public void validate(ValidationContext context) {
    String prompt = Objects.requireNonNullElse(context.getSystemPrompt(), "");
    if (prompt.contains(CONTEXT_PLACEHOLDER)) {
      context.setSystemPrompt(prompt);
      return;
    }

    String amendedPrompt = prompt.isBlank() ? CONTEXT_PLACEHOLDER : prompt + "\n\n" + CONTEXT_PLACEHOLDER;
    context.setSystemPrompt(amendedPrompt);
    context.addNotice("Injected missing {{CONTEXT}} placeholder into system prompt.");
  }
}
