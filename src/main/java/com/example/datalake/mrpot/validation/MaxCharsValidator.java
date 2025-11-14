package com.example.datalake.mrpot.validation;

import java.util.Objects;
import org.springframework.stereotype.Component;

/** Ensures the processed input does not exceed a maximum length. */
@Component
public class MaxCharsValidator implements Validator {

  private static final int DEFAULT_MAX_CHARS = 300;

  private final int maxChars;

  public MaxCharsValidator() {
    this(DEFAULT_MAX_CHARS);
  }

  public MaxCharsValidator(int maxChars) {
    if (maxChars <= 0) {
      throw new IllegalArgumentException("maxChars must be positive");
    }
    this.maxChars = maxChars;
  }

  @Override
  public ValidationStage stage() {
    return ValidationStage.PRE_CHAIN;
  }

  @Override
  public void validate(ValidationContext context) {
    String processed = Objects.requireNonNullElse(context.getProcessedInput(), "");
    if (processed.length() <= maxChars) {
      context.setProcessedInput(processed);
      return;
    }

    String truncated = processed.substring(0, maxChars);
    context.setProcessedInput(truncated);
    context.addNotice(
        String.format(
            "Input truncated to %d characters to satisfy platform limits.", maxChars));
  }
}
