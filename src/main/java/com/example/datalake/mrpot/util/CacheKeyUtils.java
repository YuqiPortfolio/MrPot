package com.example.datalake.mrpot.util;

import com.example.datalake.mrpot.model.ProcessingContext;

import static com.example.datalake.mrpot.util.PromptRenderUtils.languageCode;

public final class CacheKeyUtils {

  private CacheKeyUtils() {}

  public static String buildKey(ProcessingContext ctx) {
    String normalized = ctx.getNormalized();
    String raw = ctx.getRawInput();
    String base = (normalized == null || normalized.isBlank()) ? raw : normalized;
    String key = normalizeKey(base);
    if (key == null) {
      return null;
    }
    String lang = languageCode(ctx);
    String scope = userScope(ctx);
    return lang + "::" + scope + "::" + key;
  }

  public static String normalizeKey(String key) {
    if (key == null) {
      return null;
    }
    String trimmed = key.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String userScope(ProcessingContext ctx) {
    String userId = ctx.getUserId();
    if (userId != null && !userId.isBlank()) {
      return "user:" + userId.trim();
    }
    String sessionId = ctx.getSessionId();
    if (sessionId != null && !sessionId.isBlank()) {
      return "session:" + sessionId.trim();
    }
    return "anon";
  }
}

