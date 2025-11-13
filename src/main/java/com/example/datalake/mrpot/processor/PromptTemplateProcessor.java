package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.ProcessingContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PromptTemplateProcessor
 *
 * Responsibilities:
 * 1) Select prompt template by (language × intent) with graceful fallbacks.
 * 2) Interpolate {{placeholders}} using the ProcessingContext.
 * 3) Rewrite system prompt but preserve the original (saved in ctx metadata if available; otherwise no-op).
 * 4) Enrich prompts with keyword list (top-N keywords) for better retrieval / grounding.
 *
 * Inputs expected (best-effort; all are optional and read via reflection-safe helpers):
 *  - ctx.language or ctx.getLanguageCode(): e.g. "en", "zh"
 *  - ctx.intent: a short string like "qa", "coding", "summary"
 *  - ctx.keywords: List<String>
 *  - ctx.indexText / ctx.normalized / ctx.rawInput: the working user text
 *  - ctx.systemPrompt (existing system prompt)
 *  - ctx.userPrompt  (existing user prompt)
 *
 * Outputs:
 *  - ctx.systemPrompt (rewritten)
 *  - ctx.userPrompt   (enriched)
 *  - meta snapshot of original system prompt if a metadata map exists on ctx
 */
@Component
@RequiredArgsConstructor
public class PromptTemplateProcessor implements TextProcessor {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateProcessor.class);

    @Override public String name() { return "prompt-template"; }

    // -------- Template loading (classpath JSON) --------
    private static final String TEMPLATE_RESOURCE = "prompt_templates.json";
    private volatile TemplateIndex cachedIndex;

    // -------- Rendering --------
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");

    // Default knobs
    private static final String DEFAULT_LANG = "en";
    private static final String DEFAULT_INTENT = "general";
    private static final int    DEFAULT_TOP_K  = 8;

    @Override
    public Mono<ProcessingContext> process(ProcessingContext ctx) {
        return Mono.fromSupplier(() -> {
            ensureTemplatesLoaded();

            final String lang = pickLanguage(ctx);
            final String intent = pickIntent(ctx);

            // fetch template: lang+intent → fallback(s)
            PromptTemplate tpl = selectTemplate(lang, intent);
            if (tpl == null) {
                log.warn("No template found for lang={}, intent={}, using empty template.", lang, intent);
                tpl = new PromptTemplate("", "");
            }

            // Build variable bag from context
            Map<String, Object> vars = buildVarsFromContext(ctx, lang, intent);

            // Render system/user
            String oldSystem = tryGetString(ctx, "getSystemPrompt", "systemPrompt");
            // Preserve original system prompt if a metadata map exists
            tryPutMeta(ctx, "systemPrompt.before", oldSystem);

            String newSystem = render(tpl.system, vars);
            String newUser   = render(tpl.user,   vars);

            // Enrich with keywords (append in a consistent, minimal way)
            List<String> keywords = readKeywords(ctx);
            if (!keywords.isEmpty()) {
                List<String> top = topKeywords(keywords, DEFAULT_TOP_K);
                String joined = String.join(", ", top);

                if (isBlank(newSystem)) newSystem = "";
                if (isBlank(newUser))   newUser   = safeReadUserText(ctx);

                // Insert a compact, clearly marked block
                newSystem = appendBlock(newSystem, "Keywords", joined);
                newUser   = appendBlock(newUser,   "Top keywords", joined);

                // Expose for downstream processors (if map present)
                tryPutMeta(ctx, "keywords.top", top);
            }

            // Write back
            trySetString(ctx, "setSystemPrompt", "systemPrompt", newSystem);
            trySetString(ctx, "setUserPrompt", "userPrompt", newUser);

            return ctx;
        });
    }

    // ---------- Core helpers ----------

    private void ensureTemplatesLoaded() {
        if (cachedIndex != null) return;
        synchronized (this) {
            if (cachedIndex != null) return;
            try (InputStream in = new ClassPathResource(TEMPLATE_RESOURCE).getInputStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                cachedIndex = TemplateIndex.parse(json);
                log.info("Loaded prompt templates: {} languages", cachedIndex.languages());
            } catch (Exception e) {
                log.error("Failed to load prompt templates from {}", TEMPLATE_RESOURCE, e);
                cachedIndex = TemplateIndex.empty();
            }
        }
    }

    private String pickLanguage(ProcessingContext ctx) {
        // Try common getters; fall back to default
        String lang = tryGetString(ctx,
                "getIndexLanguage", "getLanguageCode", "getLanguage", "language", "indexLanguage");
        if (isBlank(lang)) lang = DEFAULT_LANG;
        lang = lang.toLowerCase(Locale.ROOT);
        // normalize like "en-US" => "en"
        int dash = lang.indexOf('-');
        if (dash > 0) lang = lang.substring(0, dash);
        return lang;
    }

    private String pickIntent(ProcessingContext ctx) {
        String intent = tryGetString(ctx, "getIntent", "intent");
        return isBlank(intent) ? DEFAULT_INTENT : intent.toLowerCase(Locale.ROOT);
    }

    private PromptTemplate selectTemplate(String lang, String intent) {
        // 1) exact lang + intent
        PromptTemplate t = cachedIndex.get(lang, intent);
        if (t != null) return t;

        // 2) language-level default for that lang
        t = cachedIndex.get(lang, DEFAULT_INTENT);
        if (t != null) return t;

        // 3) English fallback for that intent
        t = cachedIndex.get(DEFAULT_LANG, intent);
        if (t != null) return t;

        // 4) global default
        return cachedIndex.get(DEFAULT_LANG, DEFAULT_INTENT);
    }

    private Map<String, Object> buildVarsFromContext(ProcessingContext ctx, String lang, String intent) {
        Map<String, Object> v = new LinkedHashMap<>();

        String indexText = tryGetString(ctx, "getIndexText", "indexText");
        String normalized = tryGetString(ctx, "getNormalized", "normalized");
        String raw = tryGetString(ctx, "getRawInput", "rawInput");

        v.put("language", lang);
        v.put("intent", intent);
        v.put("query", firstNonBlank(indexText, normalized, raw));
        v.put("original", raw);
        v.put("normalized", normalized);
        v.put("indexText", indexText);

        // existing prompts (if any)
        v.put("system_prompt", tryGetString(ctx, "getSystemPrompt", "systemPrompt"));
        v.put("user_prompt",   tryGetString(ctx, "getUserPrompt", "userPrompt"));

        // keywords
        List<String> keywords = readKeywords(ctx);
        v.put("keywords", keywords);
        v.put("top_keywords", topKeywords(keywords, DEFAULT_TOP_K));

        // room for more (use metadata map if available)
        Object domain = tryGetMeta(ctx, "domain");
        if (domain != null) v.put("domain", domain.toString());

        return v;
    }

    private String render(String template, Map<String, Object> vars) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object val = resolve(key, vars);
            String rep = val == null ? "" : stringify(val);
            // escape backslashes and dollar signs for appendReplacement
            rep = rep.replace("\\", "\\\\").replace("$", "\\$");
            m.appendReplacement(out, rep);
        }
        m.appendTail(out);
        return out.toString().trim();
    }

    private Object resolve(String dottedKey, Map<String, Object> vars) {
        if (!dottedKey.contains(".")) return vars.get(dottedKey);
        // nested: a.b.c -> walk maps
        String[] parts = dottedKey.split("\\.");
        Object cur = vars;
        for (String p : parts) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(p);
            if (cur == null) return null;
        }
        return cur;
    }

    private String stringify(Object o) {
        if (o == null) return "";
        if (o instanceof Collection<?> c) {
            List<String> s = new ArrayList<>(c.size());
            for (Object e : c) s.add(Objects.toString(e, ""));
            return String.join(", ", s);
        }
        return Objects.toString(o, "");
    }

    private List<String> readKeywords(ProcessingContext ctx) {
        Object ks = tryGet(ctx, "getKeywords", "keywords");
        if (ks instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null) out.add(o.toString());
            return out;
        }
        return Collections.emptyList();
    }

    private List<String> topKeywords(List<String> keywords, int k) {
        if (keywords.isEmpty() || k <= 0) return Collections.emptyList();
        // Simple heuristic: stable order, prioritize uniqueness and longer tokens
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String s : keywords) {
            String t = safeTrim(s);
            if (!t.isEmpty()) uniq.add(t);
        }
        List<String> pool = new ArrayList<>(uniq);
        pool.sort((a, b) -> Integer.compare(b.length(), a.length()));
        if (pool.size() > k) pool = pool.subList(0, k);
        return pool;
    }

    private String appendBlock(String base, String title, String content) {
        if (isBlank(content)) return base;
        StringBuilder sb = new StringBuilder();
        sb.append(base == null ? "" : base.trim());
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("### ").append(title).append("\n").append(content.trim());
        return sb.toString();
    }

    private String safeReadUserText(ProcessingContext ctx) {
        return firstNonBlank(
                tryGetString(ctx, "getUserPrompt", "userPrompt"),
                tryGetString(ctx, "getIndexText", "indexText"),
                tryGetString(ctx, "getNormalized", "normalized"),
                tryGetString(ctx, "getRawInput", "rawInput")
        );
    }

    // ---------- Reflection-safe ctx IO (no compile-time dependency on your exact method names) ----------

    private Object tryGet(ProcessingContext ctx, String... methodNames) {
        for (String m : methodNames) {
            try {
                var md = ctx.getClass().getMethod(m);
                return md.invoke(ctx);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                log.debug("tryGet {} failed: {}", m, t.toString());
            }
        }
        return null;
    }

    private String tryGetString(ProcessingContext ctx, String... methodNames) {
        Object o = tryGet(ctx, methodNames);
        return o == null ? null : Objects.toString(o, null);
    }

    private void trySetString(ProcessingContext ctx, String setterName, String fieldName, String value) {
        // Try setter first
        try {
            var md = ctx.getClass().getMethod(setterName, String.class);
            md.invoke(ctx, value);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            log.debug("trySetString {} failed: {}", setterName, t.toString());
        }
        // Try public field
        try {
            var f = ctx.getClass().getField(fieldName);
            if (f.getType() == String.class) {
                f.set(ctx, value);
                return;
            }
        } catch (NoSuchFieldException ignored) {
        } catch (Throwable t) {
            log.debug("trySetString field {} failed: {}", fieldName, t.toString());
        }
        log.warn("Unable to write '{}' on ProcessingContext (setter '{}' or field '{}').", fieldName, setterName, fieldName);
    }

    @SuppressWarnings("unchecked")
    private Object tryGetMeta(ProcessingContext ctx, String key) {
        try {
            var md = ctx.getClass().getMethod("getMeta");
            Object meta = md.invoke(ctx);
            if (meta instanceof Map<?, ?> m) return m.get(key);
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private void tryPutMeta(ProcessingContext ctx, String key, Object value) {
        try {
            var md = ctx.getClass().getMethod("getMeta");
            Object meta = md.invoke(ctx);
            if (meta instanceof Map<?, ?>) {
                ((Map<String, Object>) meta).put(key, value);
                return;
            }
        } catch (Throwable ignored) {}
        // Optional: try setMeta(Map) if no map present
        try {
            var sm = ctx.getClass().getMethod("setMeta", Map.class);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(key, value);
            sm.invoke(ctx, m);
        } catch (Throwable ignored) {}
    }

    // ---------- Template storage / parsing (no external JSON dep needed) ----------

    /** In-memory index: lang → intent → template. */
    private record TemplateIndex(Map<String, Map<String, PromptTemplate>> idx) {
        static TemplateIndex empty() { return new TemplateIndex(new HashMap<>()); }
        int languages() { return idx.size(); }
        PromptTemplate get(String lang, String intent) {
            Map<String, PromptTemplate> m = idx.get(lang);
            return m == null ? null : m.get(intent);
        }

        static TemplateIndex parse(String json) {
            // Extremely small JSON reader tailored to the expected shape.
            // If you already use Jackson/Gson, feel free to replace with that.
            Map<String, Map<String, PromptTemplate>> out = new LinkedHashMap<>();
            Json j = Json.parse(json);
            if (!j.isObject()) return empty();
            for (String lang : j.keys()) {
                Json langNode = j.get(lang);
                if (!langNode.isObject()) continue;
                Map<String, PromptTemplate> intents = new LinkedHashMap<>();
                for (String intent : langNode.keys()) {
                    Json it = langNode.get(intent);
                    if (!it.isObject()) continue;
                    String system = it.getString("system", "");
                    String user   = it.getString("user", "");
                    intents.put(intent.toLowerCase(Locale.ROOT), new PromptTemplate(system, user));
                }
                out.put(lang.toLowerCase(Locale.ROOT), intents);
            }
            return new TemplateIndex(out);
        }
    }

    /** Simple pair of system/user templates. */
    private record PromptTemplate(String system, String user) {}

    // ---------- Tiny JSON helper (object/strings only) ----------
    private static final class Json {
        private final Object node;
        private Json(Object node) { this.node = node; }
        static Json parse(String s) { return new Json(new Parser(s).parseValue()); }
        boolean isObject() { return node instanceof Map; }
        Set<String> keys() { return isObject() ? ((Map<String, Object>) node).keySet() : Collections.emptySet(); }
        Json get(String k) {
            if (!isObject()) return new Json(null);
            return new Json(((Map<?, ?>) node).get(k));
        }
        String getString(String k, String def) {
            if (!isObject()) return def;
            Object v = ((Map<?, ?>) node).get(k);
            return v == null ? def : v.toString();
        }

        // Ultra-lightweight JSON parser for this limited shape
        private static final class Parser {
            private final String s; private int i;
            Parser(String s) { this.s = s; this.i = 0; }
            Object parseValue() {
                skipWs();
                if (i >= s.length()) return null;
                char c = s.charAt(i);
                if (c == '{') return parseObject();
                if (c == '"') return parseString();
                if (c == 'n' && s.startsWith("null", i)) { i += 4; return null; }
                // not needed: arrays/numbers/bools for our file
                // skip otherwise
                return null;
            }
            Map<String, Object> parseObject() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; // {
                skipWs();
                while (i < s.length() && s.charAt(i) != '}') {
                    String key = parseString();
                    skipWs(); expect(':'); skipWs();
                    Object val = parseValue();
                    m.put(key, val);
                    skipWs();
                    if (i < s.length() && s.charAt(i) == ',') { i++; skipWs(); }
                }
                if (i < s.length() && s.charAt(i) == '}') i++;
                return m;
            }
            String parseString() {
                if (s.charAt(i) != '"') return "";
                i++; StringBuilder b = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\' && i < s.length()) {
                        char n = s.charAt(i++);
                        switch (n) {
                            case '"': b.append('"'); break;
                            case '\\': b.append('\\'); break;
                            case '/': b.append('/'); break;
                            case 'b': b.append('\b'); break;
                            case 'f': b.append('\f'); break;
                            case 'n': b.append('\n'); break;
                            case 'r': b.append('\r'); break;
                            case 't': b.append('\t'); break;
                            case 'u':
                                if (i + 4 <= s.length()) {
                                    String hex = s.substring(i, i + 4);
                                    b.append((char) Integer.parseInt(hex, 16));
                                    i += 4;
                                }
                                break;
                            default: b.append(n); break;
                        }
                    } else {
                        b.append(c);
                    }
                }
                return b.toString();
            }
            void expect(char c) { if (i < s.length() && s.charAt(i) == c) i++; }
            void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        }
    }

    // ---------- tiny utils ----------
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String firstNonBlank(String... ss) {
        for (String s : ss) if (!isBlank(s)) return s;
        return "";
    }
    private static String safeTrim(String s) { return s == null ? "" : s.trim(); }
}
