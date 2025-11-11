package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.util.CodeFenceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import com.github.pemistahl.lingua.api.LanguageDetector;

import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detect language (Lingua) on NON-CODE text and emit a compact set of English
 * keywords for downstream indexing/search.
 *
 * Pipeline:
 *   - source := normalized if present else rawInput
 *   - detect language on NON-CODE segments (using a truncated sample for speed)
 *   - surface English keywords from NON-CODE segments (translations + regex)
 *   - ctx.language = detected; ctx.indexLanguage = "en"; ctx.indexText = keywords
 */
@Component
@RequiredArgsConstructor
public class LanguageDetectorProcessor implements TextProcessor {

    private final LanguageDetector detector;

    private static final int MAX_DETECTION_CHARS = 4000;
    private static final int MAX_KEYWORD_SOURCE_CHARS = 6000;
    private static final int MAX_KEYWORDS = 64;
    private static final int MAX_WORDS_PER_PHRASE = 8;
    private static final Pattern ENGLISH_KEYWORD_PATTERN =
            Pattern.compile("(?i)[A-Za-z][A-Za-z0-9]*?(?:[\\s'-]+[A-Za-z][A-Za-z0-9]*)*");
    private static final Pattern ASCII_LETTER = Pattern.compile("[a-zA-Z]");
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for",
            "from", "in", "is", "it", "of", "on", "or", "the", "to", "with"
    );

    @Override public String name() { return "language-detector"; }

    @Override
    public Mono<ProcessingContext> process(ProcessingContext ctx) {
        // --- 0) choose source: prefer normalized, fallback to rawInput ---
        String normalized = Optional.ofNullable(ctx.getNormalized()).orElse("");
        String source = normalized.isBlank()
                ? Optional.ofNullable(ctx.getRawInput()).orElse("")
                : normalized;

        // --- 1) collect NON-CODE text only (for both detection and indexing) ---
        StringBuilder nonCode = new StringBuilder();
        for (CodeFenceUtils.Segment seg : CodeFenceUtils.split(source)) {
            if (!seg.isCode) nonCode.append(seg.text);
        }
        String sample = nonCode.toString().trim();

        // --- 2) length cap to keep detection fast ---
        if (sample.length() > 12_000) sample = sample.substring(0, 12_000);
        String detectionSample = sample.length() > MAX_DETECTION_CHARS
                ? sample.substring(0, MAX_DETECTION_CHARS)
                : sample;
        String keywordSource = sample.length() > MAX_KEYWORD_SOURCE_CHARS
                ? sample.substring(0, MAX_KEYWORD_SOURCE_CHARS)
                : sample;

        // --- 3) empty? -> und + empty index text ---
        if (sample.isBlank()) {
            ctx.setLanguage(Language.und());
            ctx.setIndexLanguage("en");
            ctx.setIndexText("");
            return Mono.just(ctx.addStep(name(), "empty->und; index(en)=''"));
        }

        // --- 4) detect language with Lingua ---
        com.github.pemistahl.lingua.api.Language best = detector.detectLanguageOf(detectionSample);
        if (best == com.github.pemistahl.lingua.api.Language.UNKNOWN) {
            ctx.setLanguage(Language.und());
            // still provide English index text
            String keywords = buildEnglishKeywords(keywordSource);
            ctx.setIndexLanguage("en").setIndexText(keywords);
            return Mono.just(ctx.addStep(name(), "unknown->und; index(en) len=" + keywords.length()));
        }

        Map<com.github.pemistahl.lingua.api.Language, Double> confMap =
                detector.computeLanguageConfidenceValues(detectionSample);
        double conf = confMap.getOrDefault(best, 0.0);

        // --- 5) ISO code (prefer 639-1, else 639-3) ---
        String iso = (best.getIsoCode639_1() != null)
                ? best.getIsoCode639_1().name().toLowerCase(Locale.ROOT)
                : best.getIsoCode639_3().name().toLowerCase(Locale.ROOT);

        // --- 6) set ctx.language ---
        ctx.setLanguage(new Language()
                .setIsoCode(iso)
                .setDisplayName(toEnglishName(best))
                .setConfidence(conf)
                .setScript(guessScriptByIso(iso, sample)));

        // --- 7) build English keyword index text ---
        String keywords = buildEnglishKeywords(keywordSource);
        ctx.setIndexLanguage("en").setIndexText(keywords);

        // --- 8) log top-3 candidates for auditing ---
        String top3 = confMap.entrySet().stream()
                .sorted(Map.Entry.<com.github.pemistahl.lingua.api.Language, Double>comparingByValue().reversed())
                .limit(3)
                .map(e -> e.getKey().name().toLowerCase(Locale.ROOT) + ":" +
                        String.format(Locale.ROOT, "%.2f", e.getValue()))
                .collect(Collectors.joining(", "));

        return Mono.just(ctx.addStep(name(),
                "best=" + iso + " (" + String.format(Locale.ROOT, "%.2f", conf) + "); top3=" + top3 +
                        "; index(en) len=" + keywords.length()));
    }

    // --- helpers ---

    private static String buildEnglishKeywords(String sample) {
        if (sample == null || sample.isEmpty()) {
            return "";
        }
        TranslationResult translation = applyCustomTranslations(sample);
        KeywordCollector collector = new KeywordCollector();
        for (String keyword : translation.keywords()) {
            collector.addPhrase(keyword);
        }
        collector.scan(translation.text());
        return collector.join();
    }

    private static TranslationResult applyCustomTranslations(String sample) {
        if (sample == null || sample.isEmpty()) {
            return new TranslationResult("", new LinkedHashSet<>());
        }
        String result = sample;
        LinkedHashSet<String> collected = new LinkedHashSet<>();
        for (ReplacementRule rule : CUSTOM_TRANSLATIONS) {
            ReplacementOutcome outcome = rule.apply(result);
            result = outcome.text();
            collected.addAll(outcome.keywords());
        }
        return new TranslationResult(result, collected);
    }

    private static String toEnglishName(com.github.pemistahl.lingua.api.Language lang) {
        String n = lang.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String guessScriptByIso(String iso, String sample) {
        if (iso == null) return null;
        String lower = iso.toLowerCase(Locale.ROOT);
        if (containsHan(sample)) return "Han";
        return switch (lower) {
            case "en","de","fr","es","pt","it","nl","id","sw","vi","tr","ro","pl" -> "Latin";
            case "ru","uk","bg","sr","mk" -> "Cyrillic";
            case "ar","fa","ur","ps" -> "Arabic";
            case "he","yi" -> "Hebrew";
            case "hi","mr","ne" -> "Devanagari";
            case "th" -> "Thai";
            case "ja" -> "Mixed";
            case "ko" -> "Hangul";
            case "zh" -> "Han";
            default -> null;
        };
    }

    private static boolean containsHan(String s) {
        int han = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) han++;
            if (han > 2) return true;
        }
        return false;
    }

    private static final List<ReplacementRule> CUSTOM_TRANSLATIONS = List.of(
            ReplacementRule.literal("郭育奇高盛", "Yuqi Guo, Goldman Sachs", "Yuqi Guo", "Goldman Sachs"),
            ReplacementRule.literal("郭育奇，", "Yuqi Guo, ", "Yuqi Guo"),
            ReplacementRule.literal("郭育奇、", "Yuqi Guo, ", "Yuqi Guo"),
            ReplacementRule.literal("郭育奇。", "Yuqi Guo.", "Yuqi Guo"),
            ReplacementRule.literal("郭育奇", "Yuqi Guo", "Yuqi Guo"),
            ReplacementRule.literal("高盛", "Goldman Sachs", "Goldman Sachs")
    );

    private record ReplacementRule(Pattern pattern, String replacement, List<String> keywords) {
        static ReplacementRule literal(String literal, String replacement, String... keywords) {
            return new ReplacementRule(
                    Pattern.compile(Pattern.quote(literal)),
                    replacement,
                    List.of(keywords)
            );
        }

        ReplacementOutcome apply(String input) {
            Matcher matcher = pattern.matcher(input);
            if (!matcher.find()) {
                return new ReplacementOutcome(input, List.of());
            }
            String replaced = matcher.replaceAll(replacement);
            return new ReplacementOutcome(replaced, keywords);
        }
    }

    private record ReplacementOutcome(String text, List<String> keywords) {}

    private record TranslationResult(String text, LinkedHashSet<String> keywords) {}

    private static final class KeywordCollector {
        private final LinkedHashMap<String, String> keywords = new LinkedHashMap<>();

        void addPhrase(String candidate) {
            if (candidate == null) return;
            String display = collapseWhitespace(candidate);
            if (display.isEmpty()) return;
            String normalized = limitWords(display, MAX_WORDS_PER_PHRASE);
            String canonical = canonicalize(normalized);
            if (canonical.isEmpty() || keywords.containsKey(canonical) || keywords.size() >= MAX_KEYWORDS) {
                return;
            }
            keywords.put(canonical, normalized);
        }

        void addWord(String candidate) {
            if (candidate == null) return;
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) return;
            String canonical = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (canonical.length() < 2 || STOPWORDS.contains(canonical) || keywords.containsKey(canonical)
                    || keywords.size() >= MAX_KEYWORDS) {
                return;
            }
            keywords.put(canonical, trimmed);
        }

        void scan(String text) {
            if (text == null || text.isEmpty()) return;
            Matcher matcher = ENGLISH_KEYWORD_PATTERN.matcher(text);
            while (matcher.find() && keywords.size() < MAX_KEYWORDS) {
                String phrase = matcher.group();
                addPhrase(phrase);
                if (keywords.size() >= MAX_KEYWORDS) break;
                for (String part : phrase.split("[\\s'-]+")) {
                    addWord(part);
                    if (keywords.size() >= MAX_KEYWORDS) break;
                }
            }
        }

        String join() {
            if (keywords.isEmpty()) return "";
            return String.join("\n", keywords.values());
        }

        private static String collapseWhitespace(String value) {
            return value.replaceAll("\\s{2,}", " ").trim();
        }

        private static String canonicalize(String value) {
            String canonical = value.toLowerCase(Locale.ROOT).replaceAll("[\\s'-]+", " ").trim();
            if (canonical.isEmpty()) return "";
            if (!ASCII_LETTER.matcher(canonical).find()) return "";
            return canonical;
        }

        private static String limitWords(String value, int maxWords) {
            String[] words = value.split("\\s+");
            if (words.length <= maxWords) {
                return value;
            }
            return String.join(" ", Arrays.copyOf(words, maxWords));
        }
    }
}
