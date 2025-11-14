package com.example.datalake.mrpot.validation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Coordinates all registered {@link Validator} beans and executes them in stage order for an
 * incoming request.
 */
@Service
public class ValidationService {

  private final List<Validator> orderedValidators;

  public ValidationService(List<Validator> validators) {
    List<Validator> safeValidators = validators == null ? List.of() : validators;
    this.orderedValidators = safeValidators.stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(Validator::stage))
        .toList();
  }

  public ValidationContext validate(String rawInput, String systemPrompt) {
    ValidationContext context = new ValidationContext(rawInput, systemPrompt);
    for (Validator validator : orderedValidators) {
      validator.validate(context);
    }
    return context;
  }
}
