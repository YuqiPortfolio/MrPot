package com.example.datalake.mrpot.validation;

/** Exception thrown when validation fails in a non-recoverable way. */
public class ValidationException extends RuntimeException {

  public ValidationException(String message) {
    super(message);
  }
}
