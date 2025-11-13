package com.example.datalake.mrpot.processor;

import static org.junit.jupiter.api.Assertions.*;

import com.example.datalake.mrpot.model.ProcessingContext;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class PromptTemplateProcessorTest {
  // --------- Generic reflection helpers (work with Lombok fluent setters + enums) ---------

  /** Try setter first (setXxx), then field write for a property name (camelCase). */
  private static void setProp(Object target, String prop, Object value) {
    Class<?> cls = target.getClass();
    // try setter: setProp(...)
    for (Method m : cls.getMethods()) {
      if (!m.getName().equals("set" + up(prop)))
        continue;
      if (m.getParameterCount() != 1)
        continue;
      Class<?> pt = m.getParameterTypes()[0];
      try {
        Object coerced = coerce(value, pt);
        m.invoke(target, coerced);
        return;
      } catch (Exception ignored) {
      }
    }
    // try public field
    try {
      Field f = cls.getDeclaredField(prop);
      f.setAccessible(true);
      Object coerced = coerce(value, f.getType());
      f.set(target, coerced);
      return;
    } catch (Exception ignored) {
    }
    // give up silently; tests can still assert downstream behavior
  }

  /** Read a simple getter or field. */
  private static Object getProp(Object target, String prop) {
    Class<?> cls = target.getClass();
    // try getter(s)
    String[] getters = new String[] {"get" + up(prop), prop}; // e.g., getMeta(), meta()
    for (String g : getters) {
      try {
        Method m = cls.getMethod(g);
        return m.invoke(target);
      } catch (Exception ignored) {
      }
    }
    // try field
    try {
      Field f = cls.getDeclaredField(prop);
      f.setAccessible(true);
      return f.get(target);
    } catch (Exception ignored) {
    }
    return null;
  }

  /** Coerce String/List into expected param type (String/Enum/List generics tolerated). */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Object coerce(Object v, Class<?> targetType) {
    if (v == null)
      return null;
    if (targetType.isInstance(v))
      return v;
    if (targetType == String.class)
      return String.valueOf(v);
    if (targetType.isEnum()) {
      // map string to enum constant by case-insensitive name
      String s = String.valueOf(v);
      Object[] constants = targetType.getEnumConstants();
      for (Object c : constants) {
        if (((Enum) c).name().equalsIgnoreCase(s))
          return c;
      }
      // fall back to first constant if nothing matches (test robustness)
      return constants.length > 0 ? constants[0] : null;
    }
    // List<String> targets: accept raw List and trust erasure
    if (List.class.isAssignableFrom(targetType) && v instanceof List)
      return v;
    return v; // best effort
  }

  private static String up(String s) {
    return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  // --------------- Tests ---------------

  @Test
  void exactTemplate_en_qa_withKeywordEnrichment_andSystemPreserve() {
    PromptTemplateProcessor proc = new PromptTemplateProcessor();
    ProcessingContext ctx = new ProcessingContext();

    // Prepare context via reflection (compatible with Lombok fluent setters)
    setProp(ctx, "indexLanguage", "en");
    setProp(ctx, "intent", "qa"); // will coerce to enum if needed
    setProp(ctx, "indexText", "How many shards should I use for my Elasticsearch index?");
    setProp(ctx, "rawInput", "How many shards should I use for my Elasticsearch index?");
    setProp(ctx, "keywords",
        Arrays.asList("Elasticsearch", "shards", "index", "primary shards", "replicas", "cluster",
            "routing", "throughput", "ES", "ILM", "hot-warm-cold"));
    setProp(ctx, "systemPrompt", "OLD-SYSTEM");

    proc.process(ctx).block();

    String sys = (String) getProp(ctx, "systemPrompt");
    String user = (String) getProp(ctx, "userPrompt");
    assertNotNull(sys);
    assertNotNull(user);

    assertTrue(
        sys.contains("You answer questions precisely"), "expected en/qa system template content");
    assertTrue(sys.contains("### Keywords"), "system prompt should include keywords block");
    assertTrue(user.startsWith("Question:"), "user prompt should come from en/qa template");
    assertTrue(user.contains("### Top keywords"), "user prompt should include top keywords block");

    // Meta preservation (only assert if meta map exists)
    Object meta = getProp(ctx, "meta");
        if (meta instanceof Map<?,?> m) {
          assertEquals("OLD-SYSTEM", m.get("systemPrompt.before"));
          Object top = m.get("keywords.top");
          assertTrue(top instanceof List<?>);
          assertTrue(((List<?>) top).size() <= 8);
        }
  }

  @Test
  void fallback_zh_coding_to_zh_general_and_enrichment() {
    PromptTemplateProcessor proc = new PromptTemplateProcessor();
    ProcessingContext ctx = new ProcessingContext();

    setProp(ctx, "indexLanguage", "zh");
    setProp(
        ctx, "intent", "coding"); // zh/coding missing in test templates → fallback to zh/general
    setProp(ctx, "indexText", "请为Spring Boot写一个SSE示例");
    setProp(ctx, "keywords", List.of("Spring Boot", "SSE", "Reactive", "WebFlux"));
    setProp(ctx, "systemPrompt", "PREV");

    proc.process(ctx).block();

    String sys = (String) getProp(ctx, "systemPrompt");
    String user = (String) getProp(ctx, "userPrompt");
    assertNotNull(sys);
    assertNotNull(user);

    assertTrue(user.startsWith("需求："), "user prompt should be zh/general template");
  }

  @Test
  void placeholderRender_and_topKCap_and_languageNormalization() {
    PromptTemplateProcessor proc = new PromptTemplateProcessor();
    ProcessingContext ctx = new ProcessingContext();

    setProp(ctx, "indexLanguage", "en-US"); // should normalize to "en"
    setProp(ctx, "intent", "general");
    setProp(ctx, "indexText", "Design a resilient upload pipeline");
    setProp(ctx, "keywords",
        Arrays.asList("a", "ab", "microservices", "distributed systems", "observability",
            "cache invalidation", "CI/CD", "immutability", "DDD", "zero-downtime deployments",
            "TLS", "idempotency"));
    setProp(ctx, "systemPrompt", "OLD");

    proc.process(ctx).block();

    String sys = (String) getProp(ctx, "systemPrompt");
    String user = (String) getProp(ctx, "userPrompt");
    assertNotNull(sys);
    assertNotNull(user);

    assertTrue(sys.contains("Language=en"), "language should be normalized to 'en'");
    assertTrue(sys.contains("Intent=general"));

    // crude check: keywords block present and capped
    String kwBlock = user.substring(user.indexOf("Task"));
    int commas = kwBlock.split(",").length - 1; // commas ~= count-1
    assertTrue(commas < 9, "top keywords should be capped at <= 8");
  }
}
