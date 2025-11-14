package com.example.datalake.mrpot.validation;

import java.util.List;
import java.util.Objects;

/** Exception thrown when validation fails in a non-recoverable way. */
public class ValidationException extends RuntimeException {

  private final List<String> reasons;

  public ValidationException(String message) {
    super(Objects.requireNonNull(message, "message"));
    this.reasons = List.of(message);
  }

  public ValidationException(List<String> reasons) {
    super(formatMessage(reasons));
    this.reasons = List.copyOf(reasons);
  }

  public List<String> getReasons() {
    return reasons;
  }

  private static String formatMessage(List<String> reasons) {
    Objects.requireNonNull(reasons, "reasons");
    if (reasons.isEmpty()) {
      throw new IllegalArgumentException("reasons must not be empty");
    }
    if (reasons.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("reasons must not contain null entries");
    }
    return String.join("; ", reasons);
  }
}
