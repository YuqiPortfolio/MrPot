package com.example.datalake.mrpot.validation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotBlankInputValidatorTest {

  private final NotBlankInputValidator validator = new NotBlankInputValidator();

  @Test
  void shouldRejectBlankInput() {
    ValidationContext context = new ValidationContext("   ", "system");

    assertThatThrownBy(() -> validator.validate(context))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void shouldPassThroughNonBlankInput() {
    ValidationContext context = new ValidationContext("Hello", "system");

    validator.validate(context);

    assertThat(context.getProcessedInput()).isEqualTo("Hello");
  }
}
