package com.example.datalake.mrpot.sse;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.StepLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class ThinkingStepsMapper {

  public List<ThinkingStep> toThinkingSteps(ProcessingContext ctx) {
    List<StepLog> logs = Optional.ofNullable(ctx.getSteps()).orElse(List.of());
    List<ThinkingStep> out = new ArrayList<>(logs.size());

    int i = 1;
    for (StepLog log : logs) {
      ThinkingStep step = new ThinkingStep();
      step.setIndex(i++);
      step.setProcessor(log.getName());
      step.setTitle(humanTitleFor(log.getName()));

      // This is where we inject normalized text / keywords, etc.
      step.setDetail(buildDetail(log, ctx));

      step.setAt(log.getAt());
      out.add(step);
    }
    return out;
  }

  private String humanTitleFor(String name) {
    if (name == null) return "Processing";
    return switch (name) {
      case "unified-clean-correct" -> "Normalize & clean input";
      case "language-translate" -> "Detect language & translate";
      case "intent-classifier" -> "Classify intent & extract keywords";
      case "common-response" -> "Match common greeting / FAQ";
      case "prompt-template" -> "Build system + user prompt";
      case "prompt-cache-lookup" -> "Check prompt cache";
      case "online-search" -> "Search online references";
      case "langchain4j-rag" -> "Retrieve KB & call LLM";
      case "prompt-cache-record" -> "Record answer into cache";
      default -> "Processor: " + name;
    };
  }

  /**
   * Build what the frontend actually shows under each step.
   * For known processors, we pull structured data from ctx;
   * otherwise we fall back to the raw StepLog.note.
   */
  private String buildDetail(StepLog log, ProcessingContext ctx) {
    String name = log.getName();
    if (name == null) {
      return log.getNote();
    }

    return switch (name) {
      case "unified-clean-correct" -> normalizeDetail(ctx);
      case "intent-classifier" -> keywordsDetail(ctx);
      case "common-response" -> commonResponseDetail(ctx, log);
      case "online-search" -> onlineSearchDetail(ctx, log);
      case "langchain4j-rag" -> ragDetail(ctx, log);
      // you can add more here if needed:
      // case "language-translate"  -> translateDetail(ctx);
      // case "prompt-template"     -> templateDetail(ctx);
      default -> log.getNote();
    };
  }

  private String normalizeDetail(ProcessingContext ctx) {
    String normalized = ctx.getNormalized();
    String corrected = ctx.getCorrected();
    String raw = ctx.getRawInput();

    String base = firstNonBlank(normalized, corrected, raw);
    if (base == null) {
      return "(no text)";
    }

    if (base.length() > 300) {
      base = base.substring(0, 300) + "…";
    }

    double ratio = ctx.getChangeRatio();
    String ratioText = (Double.isNaN(ratio) || Double.isInfinite(ratio))
        ? ""
        : " (change " + String.format(Locale.ROOT, "%.1f%%", ratio * 100) + ")";

    return "Normalized text" + ratioText + ":\n" + base;
  }

  private String keywordsDetail(ProcessingContext ctx) {
    Intent intent = ctx.getIntent();

    List<String> keywords = Optional.ofNullable(ctx.getKeywords())
        .orElse(List.of());
    Set<String> tags = Optional.ofNullable(ctx.getTags())
        .orElse(Set.of());

    String kwText = keywords.isEmpty() ? "(none)" : String.join(", ", keywords);
    String tagText = tags.isEmpty() ? "(none)" : String.join(", ", tags);
    String inText = (intent == null) ? "UNKNOWN" : intent.name();

    return "Intent = " + inText +
        "\nKeywords = " + kwText +
        "\nTags = " + tagText;
  }

  private String commonResponseDetail(ProcessingContext ctx, StepLog log) {
    if (!ctx.isCommonResponse()) {
      return "No common response matched";
    }

    String note = Optional.ofNullable(log.getNote()).orElse("");
    String systemPrompt = Optional.ofNullable(ctx.getSystemPrompt()).orElse("(none)");
    String userPrompt = Optional.ofNullable(ctx.getUserPrompt()).orElse("(none)");

    return "Matched common response" + (note.isBlank() ? "" : ": " + note)
        + "\nSystem prompt: " + systemPrompt
        + "\nUser prompt: " + userPrompt;
  }

  private String onlineSearchDetail(ProcessingContext ctx, StepLog log) {
    String refs = Optional.ofNullable(ctx.getOnlineReferences()).orElse("").strip();
    String note = Optional.ofNullable(log.getNote()).orElse("");

    if (refs.isBlank()) {
      return note.isBlank()
          ? "KB search returned no snippets; no online references available"
          : note;
    }

    if (refs.length() > 400) {
      refs = refs.substring(0, 400) + "…";
    }
    return "Online references:\n" + refs;
  }

  private String ragDetail(ProcessingContext ctx, StepLog log) {
    List<Long> docIds = Optional.ofNullable(ctx.getLlmDocIds()).orElse(List.of());
    String docsText = docIds.isEmpty() ? "(none)" : docIds.toString();
    String note = Optional.ofNullable(log.getNote()).orElse("");

    return "Knowledge search" + (note.isBlank() ? "" : " => " + note)
        + "\nDoc IDs: " + docsText
        + "\nAnswer: " + Optional.ofNullable(ctx.getLlmAnswer()).orElse("(pending)");
  }

  @SafeVarargs
  private static <T> T firstNonBlank(T... candidates) {
    for (T c : candidates) {
      if (c instanceof String str) {
        if (str != null && !str.isBlank()) {
          return c;
        }
      } else if (c != null) {
        return c;
      }
    }
    return null;
  }
}
