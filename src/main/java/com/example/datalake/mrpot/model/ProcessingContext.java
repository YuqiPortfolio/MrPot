package com.example.datalake.mrpot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true, fluent = false)
public class ProcessingContext {
  // input
  private String userId;
  private String sessionId;
  private String rawInput;

  // normalized + features
  private String normalized;
  private Language language = Language.und();
  private Intent intent = Intent.UNKNOWN;
  private Set<String> tags = new LinkedHashSet<>();
  private Map<String, List<String>> entities = new LinkedHashMap<>();

  private String corrected;
  private Map<String, List<String>> outline = new LinkedHashMap<>();
  private double changeRatio;

  // Language
  private String indexText;
  private String indexLanguage = "en";

  // template selection
  private PromptTemplate template;
  private String systemPrompt;
  private String userPrompt;

  // assembled
  private String finalPrompt; // optional combined
  private int charLimit = 8000; // simple guard
  private Instant now = Instant.now();

  // audit trail
  private List<StepLog> steps = new ArrayList<>();
  private List<String> validationNotices = new ArrayList<>();

  // caching + short-circuit controls
  private String cacheKey;
  private boolean cacheHit;
  private int cacheFrequency;
  private boolean commonResponse;

  public ProcessingContext addStep(String name, String note) {
    steps.add(new StepLog().setName(name).setNote(note).setAt(Instant.now()));
    return this;
  }
}
