package com.example.datalake.mrpot.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single "thinking step" for UI display.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ThinkingStep {

  // 1-based index for UI
  private int index;

  // internal processor name, e.g. "unified-clean-correct"
  private String processor;

  // human-readable title
  private String title;

  // detail to show on frontend (normalized text, keywords, etc.)
  private String detail;

  // timestamp from StepLog.at
  private Instant at;
}
