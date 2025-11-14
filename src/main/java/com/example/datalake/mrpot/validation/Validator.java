package com.example.datalake.mrpot.validation;

/** Contract for validation steps executed as part of the conversation pipeline. */
public interface Validator {

  /** The stage in which the validator should be executed. */
  ValidationStage stage();

  /** Applies the validation logic and optionally mutates the provided context. */
  void validate(ValidationContext context);
}
