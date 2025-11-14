package com.example.datalake.mrpot.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaxCharsValidatorTest {

  @Test
  void shouldLeaveShortInputUntouched() {
    ValidationContext context = new ValidationContext("short", "system");
    MaxCharsValidator validator = new MaxCharsValidator(10);

    validator.validate(context);

    assertThat(context.getProcessedInput()).isEqualTo("short");
    assertThat(context.getNotices()).isEmpty();
  }

  @Test
  void shouldTruncateWhenExceedingLimit() {
    ValidationContext context = new ValidationContext("abcdefghij", "system");
    MaxCharsValidator validator = new MaxCharsValidator(5);

    validator.validate(context);

    assertThat(context.getProcessedInput()).isEqualTo("abcde");
    assertThat(context.getNotices())
        .singleElement()
        .isEqualTo("Input truncated to 5 characters to satisfy platform limits.");
  }
}
