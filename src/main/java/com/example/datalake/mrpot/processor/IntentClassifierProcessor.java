package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class IntentClassifierProcessor implements TextProcessor {

    private static final String NAME = "intent-classifier";

    // Matches either a Latin token ([a-z0-9+.#-]+) OR a contiguous CJK (Han) run (\\p{IsHan}+)
    private static final Pattern TOKENIZER = Pattern.compile("([a-z0-9+.#-]+)|([\\p{IsHan}]+)");
    private static final Set<String> GREETING_PHRASES = Set.of(
            "hi",
            "hi there",
            "hello",
            "hello there",
            "hey",
            "hey there",
            "greetings",
            "howdy",
            "hola",
            "good morning",
            "good afternoon",
            "good evening",
            "good day",
            "morning",
            "evening",
            "你好",
            "您好"
    );

    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Map<String, Set<String>> expandedLexicon;
    private final List<Rule> rules;

    public IntentClassifierProcessor(ResourceLoader resourceLoader) {
        this.expandedLexicon = loadLexicon(resourceLoader, "keywords_map.json");
        this.rules = loadRules(resourceLoader, "intent_rules.json");
    }

    @Override public String name() { return NAME; }

    @Override
    public Mono<ProcessingContext> process(ProcessingContext ctx) {
        final String text = Optional.ofNullable(ctx.getIndexText())
                .filter(s -> !s.isBlank())
                .orElseGet(() -> Optional.ofNullable(ctx.getNormalized()).orElse(""));

        if (text.isBlank()) {
            ctx.addStep(NAME, "empty text; keep UNKNOWN");
            ctx.setIntent(Intent.UNKNOWN);
            return Mono.just(ctx);
        }

        // 1) tokenize: Latin words + CJK runs; also add cross-word bigrams and Han char-grams
        List<String> tokens = tokenize(text);
        Set<String> tokenSet = new HashSet<>(tokens);

        // 2) expand tags via lexicon
        Set<String> tags = new LinkedHashSet<>(Optional.ofNullable(ctx.getTags()).orElseGet(LinkedHashSet::new));
        expandedLexicon.forEach((canonical, synonyms) -> {
            if (synonyms.stream().anyMatch(tokenSet::contains)) {
                tags.add(canonical);
            }
        });

        // 3) score rules
        Intent predicted = Intent.UNKNOWN;
        String matchedRule = null;
        int bestScore = Integer.MIN_VALUE;

        if (isGreetingText(text)) {
            predicted = Intent.GREETING;
            matchedRule = "builtin:greeting";
        } else if (!rules.isEmpty()) {
            for (Rule r : rules) {
                int score = 0;

                for (String kw : r.any) if (hit(kw, tokenSet)) score += 1;

                boolean allOk = true;
                for (String kw : r.all) if (!hit(kw, tokenSet)) { allOk = false; break; }
                if (allOk && !r.all.isEmpty()) score += 2;

                for (String kw : r.none) if (hit(kw, tokenSet)) score -= 2;

                for (String t : r.tagsBoost) if (tags.contains(t)) score += 1;

                if (score > bestScore && score >= r.minScore) {
                    bestScore = score;
                    predicted = r.intent;
                    matchedRule = r.debugName;
                }
            }
        }

        // 4) write back
        ctx.setIntent(predicted);
        tags.add("intent:" + predicted.name().toLowerCase(Locale.ROOT));
        ctx.setTags(tags);

        // 5) step log
        String note = "intent=" + predicted +
                (matchedRule != null ? (", rule=" + matchedRule) : "") +
                ", tags=" + new ArrayList<>(tags);
        ctx.addStep(NAME, note);

        return Mono.just(ctx);
    }

    // ---------------- Tokenization (Latin + CJK) ----------------

    private List<String> tokenize(String raw) {
        String text = raw.toLowerCase(Locale.ROOT);
        Matcher m = TOKENIZER.matcher(text);

        List<String> words = new ArrayList<>(); // mixed: latin words and han runs
        List<String> hanCharNgrams = new ArrayList<>();

        while (m.find()) {
            String latin = m.group(1);
            String han = m.group(2);

            if (latin != null) {
                words.add(latin);
            } else if (han != null) {
                // keep the whole Han run as a token
                words.add(han);
                // also add Han char unigrams and bigrams for recall (轻量，不引入外部分词器)
                for (int i = 0; i < han.length(); i++) {
                    hanCharNgrams.add(han.substring(i, i + 1));
                }
                for (int i = 0; i + 1 < han.length(); i++) {
                    hanCharNgrams.add(han.substring(i, i + 2));
                }
            }
        }

        // add cross-word bigrams (for phrases like "sign in")
        List<String> tokens = new ArrayList<>(words.size() * 2 + hanCharNgrams.size());
        tokens.addAll(words);
        for (int i = 1; i < words.size(); i++) {
            tokens.add(words.get(i - 1) + " " + words.get(i));
        }
        // add Han char-grams
        tokens.addAll(hanCharNgrams);

        return tokens;
    }

    private boolean hit(String kw, Set<String> tokenSet) {
        if (tokenSet.contains(kw)) return true;
        Set<String> syn = expandedLexicon.get(kw);
        if (syn != null) for (String s : syn) if (tokenSet.contains(s)) return true;
        return false;
    }

    private boolean isGreetingText(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        StringBuilder cleaned = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || Character.isWhitespace(c) || c == '\u4f60' || c == '\u597d' || c == '\u60a8') {
                cleaned.append(c);
            }
        }
        String normalized = cleaned.toString().replaceAll("\\s+", " ").trim();
        return GREETING_PHRASES.contains(normalized);
    }

    // ---------------- Rules & Lexicon Loading ----------------

    private List<Rule> loadRules(ResourceLoader rl, String classpathName) {
        try (InputStream in = openForRead(rl, classpathName,
                "intent.rules", "INTENT_RULES_PATH", "src/main/resources/" + classpathName)) {
            if (in == null) {
                log.info("No intent rules found for {}", classpathName);
                return Collections.emptyList();
            }
            RuleBundle rb = om.readValue(in, RuleBundle.class);
            return toRules(rb);
        } catch (Exception e) {
            log.warn("Failed to load intent rules {} – {}", classpathName, e.toString());
            return Collections.emptyList();
        }
    }

    private Map<String, Set<String>> loadLexicon(ResourceLoader rl, String classpathName) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        try (InputStream in = openForRead(rl, classpathName,
                "keywords.map.path", "KEYWORDS_MAP_PATH", "src/main/resources/" + classpathName)) {
            if (in == null) {
                log.info("No lexicon found for {}", classpathName);
                return out;
            }
            LexiconBundle bundle = om.readValue(in, LexiconBundle.class);
            if (bundle.terms != null) {
                for (Map.Entry<String, TermEntry> e : bundle.terms.entrySet()) {
                    String canonical = e.getKey().toLowerCase(Locale.ROOT);
                    Set<String> syns = new LinkedHashSet<>();
                    syns.add(canonical);
                    if (e.getValue() != null) {
                        if (e.getValue().D != null) e.getValue().D.forEach(s -> addLower(syns, s));
                        if (e.getValue().C != null) e.getValue().C.forEach(s -> addLower(syns, s));
                    }
                    out.put(canonical, syns);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to load lexicon {} – {}", classpathName, e.toString());
            return out;
        }
    }

    private InputStream openForRead(ResourceLoader rl,
                                    String classpathName,
                                    String sysPropKey,
                                    String envKey,
                                    String lastResortPath) {
        try {
            // 1) Spring ResourceLoader: classpath:
            Resource r = rl.getResource("classpath:" + classpathName);
            if (r.exists()) return r.getInputStream();
        } catch (Exception ignore) { }

        try {
            // 2) ClassLoader (works in tests & shaded jars)
            InputStream in = IntentClassifierProcessor.class.getClassLoader().getResourceAsStream(classpathName);
            if (in != null) return in;
        } catch (Exception ignore) { }

        try {
            // 3) External path override: -D{sysPropKey}=... or ENV {envKey}
            String external = Optional.ofNullable(System.getProperty(sysPropKey))
                    .orElse(System.getenv(envKey));
            if (external != null && !external.isBlank()) {
                File f = new File(external);
                if (f.exists() && f.isFile()) return new FileInputStream(f);
            }
        } catch (Exception ignore) { }

        try {
            // 4) Last resort: direct project path (useful in IDE runs)
            File f = new File(lastResortPath);
            if (f.exists() && f.isFile()) return new FileInputStream(f);
        } catch (Exception ignore) { }

        return null;
    }

    private static void addLower(Set<String> set, String s) {
        if (s != null && !s.isBlank()) set.add(s.toLowerCase(Locale.ROOT));
    }

    private List<Rule> toRules(RuleBundle bundle) {
        if (bundle == null || bundle.rules == null) return Collections.emptyList();
        List<Rule> rs = new ArrayList<>(bundle.rules.size());
        for (RuleJson rj : bundle.rules) {
            String intentName = (rj.intent == null) ? "OTHER" : rj.intent.trim().toUpperCase(Locale.ROOT);
            Intent intent;
            try { intent = Intent.valueOf(intentName); }
            catch (IllegalArgumentException ex) { intent = Intent.UNKNOWN; }
            rs.add(new Rule(
                    intent,
                    (rj.minScore == null ? 1 : rj.minScore),
                    toLowerList(rj.any),
                    toLowerList(rj.all),
                    toLowerList(rj.none),
                    toLowerList(rj.tagsBoost),
                    (rj.name != null && !rj.name.isBlank()) ? rj.name : intent.name()
            ));
        }
        return rs;
    }

    private static List<String> toLowerList(List<String> in) {
        if (in == null) return List.of();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) if (s != null && !s.isBlank()) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RuleBundle { public List<RuleJson> rules; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RuleJson {
        public String name;       // optional debug name
        public String intent;     // e.g., "LOGIN"
        public Integer minScore;  // default 1
        public List<String> any;
        public List<String> all;
        public List<String> none;
        public List<String> tagsBoost;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LexiconBundle { public Map<String, TermEntry> terms; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TermEntry {
        public List<String> D; // domain terms / synonyms
        public List<String> C; // contextual terms / synonyms
    }

    // Internal rule model
    @Data @AllArgsConstructor
    private static class Rule {
        final Intent intent;
        final int minScore;
        final List<String> any;
        final List<String> all;
        final List<String> none;
        final List<String> tagsBoost;
        final String debugName;
    }

}
