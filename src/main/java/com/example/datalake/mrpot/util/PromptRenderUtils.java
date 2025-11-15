package com.example.datalake.mrpot.util;

import com.example.datalake.mrpot.model.ProcessingContext;

import java.util.Locale;
import java.util.Objects;

public final class PromptRenderUtils {
  private PromptRenderUtils() {}

  private static final String BASE_SYSTEM_PROMPT = """
          You are Mr Pot, an AI agent focused on helping users quickly. Keep answers concise.
          """.trim();

  public static String baseSystemPrompt() {
    return BASE_SYSTEM_PROMPT;
  }

  public static String ensureSystemPrompt(ProcessingContext ctx) {
    String sysPrompt = ctx.getSystemPrompt();
    if (sysPrompt == null || sysPrompt.isBlank()) {
      sysPrompt = BASE_SYSTEM_PROMPT;
      ctx.setSystemPrompt(sysPrompt);
    }
    return sysPrompt;
  }

  public static String ensureUserPrompt(ProcessingContext ctx) {
    String userPrompt = ctx.getUserPrompt();
    if (userPrompt == null || userPrompt.isBlank()) {
      String normalized = ctx.getNormalized();
      String raw = ctx.getRawInput();
      String text = (normalized == null || normalized.isBlank()) ? raw : normalized;
      String userId = ctx.getUserId();
      String label = (userId == null || userId.isBlank()) ? "anonymous" : userId;
      userPrompt = "User(" + label + "): " + (text == null ? "" : text);
      ctx.setUserPrompt(userPrompt);
    }
    return userPrompt;
  }

  public static String ensureFinalPrompt(ProcessingContext ctx) {
    String finalPrompt = ctx.getFinalPrompt();
    if (finalPrompt == null || finalPrompt.isBlank()) {
      String sysPrompt = ensureSystemPrompt(ctx);
      String userPrompt = ensureUserPrompt(ctx);
      finalPrompt = assembleFinalPrompt(sysPrompt, userPrompt);
      ctx.setFinalPrompt(finalPrompt);
    }
    return finalPrompt;
  }

  public static String assembleFinalPrompt(String systemPrompt, String userPrompt) {
    String sys = Objects.toString(systemPrompt, "");
    String user = Objects.toString(userPrompt, "");
    if (sys.isBlank()) {
      return user;
    }
    if (user.isBlank()) {
      return sys;
    }
    return sys + "\n---\n" + user;
  }

  public static String languageCode(ProcessingContext ctx) {
    if (ctx.getLanguage() != null) {
      String iso = ctx.getLanguage().getIsoCode();
      if (iso != null && !iso.isBlank()) {
        return iso.toLowerCase(Locale.ROOT);
      }
    }
    String idxLang = ctx.getIndexLanguage();
    if (idxLang != null && !idxLang.isBlank()) {
      return idxLang.toLowerCase(Locale.ROOT);
    }
    return "en";
  }
}
