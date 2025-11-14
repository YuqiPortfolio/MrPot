package com.example.datalake.mrpot.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Carries the user supplied input and related metadata through the validation stages. Validators
 * can mutate the processed input or system prompt and attach user facing notices.
 */
public class ValidationContext {

  private final String rawInput;
  private String processedInput;
  private String systemPrompt;
  private final List<String> notices = new ArrayList<>();

  public ValidationContext(String rawInput, String systemPrompt) {
    this.rawInput = rawInput;
    this.processedInput = rawInput;
    this.systemPrompt = systemPrompt;
  }

  public String getRawInput() {
    return rawInput;
  }

  public String getProcessedInput() {
    return processedInput;
  }

  public void setProcessedInput(String processedInput) {
    this.processedInput = processedInput;
  }

  public String getSystemPrompt() {
    return systemPrompt;
  }

  public void setSystemPrompt(String systemPrompt) {
    this.systemPrompt = systemPrompt;
  }

  /** Adds a user visible notice emitted during validation. */
  public void addNotice(String notice) {
    notices.add(Objects.requireNonNull(notice, "notice"));
  }

  public List<String> getNotices() {
    return Collections.unmodifiableList(notices);
  }
}
