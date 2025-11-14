package com.example.datalake.mrpot.validation;

import org.springframework.stereotype.Component;

/** Validates that the user supplied raw input is not null or blank. */
@Component
public class NotBlankInputValidator implements Validator {

  @Override
  public ValidationStage stage() {
    return ValidationStage.PRE_CHAIN;
  }

  @Override
  public void validate(ValidationContext context) {
    String rawInput = context.getRawInput();
    if (rawInput == null || rawInput.trim().isEmpty()) {
      throw new ValidationException("User input must not be blank.");
    }
    context.setProcessedInput(rawInput);
  }
}
