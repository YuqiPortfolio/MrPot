package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.util.CodeFenceUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import com.github.pemistahl.lingua.api.LanguageDetector;
import com.ibm.icu.text.Transliterator;

import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * Detect language (Lingua) on NON-CODE text and produce an English-normalized
 * index text using ICU4J transliteration to Latin/ASCII.
 *
 * Pipeline:
 *   - source := normalized if present else rawInput
 *   - detect language on NON-CODE segments
 *   - transliterate NON-CODE segments to Latin/ASCII, lowercase, strip symbols
 *   - ctx.language = detected; ctx.indexLanguage = "en"; ctx.indexText = ascii text
 */
@Component
@RequiredArgsConstructor
public class LanguageDetectorProcessor implements TextProcessor {

    private final LanguageDetector detector;

    /** ICU4J transliterator: Any script → Latin → ASCII (remove diacritics). */
    private static final Transliterator TO_ASCII =
            Transliterator.getInstance("Any-Latin; Latin-ASCII");

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

        // --- 3) empty? -> und + empty index text ---
        if (sample.isBlank()) {
            ctx.setLanguage(Language.und());
            ctx.setIndexLanguage("en");
            ctx.setIndexText("");
            return Mono.just(ctx.addStep(name(), "empty->und; index(en)=''"));
        }

        // --- 4) detect language with Lingua ---
        com.github.pemistahl.lingua.api.Language best = detector.detectLanguageOf(sample);
        if (best == com.github.pemistahl.lingua.api.Language.UNKNOWN) {
            ctx.setLanguage(Language.und());
            // still provide English index text
            String ascii = toAsciiIndex(sample);
            ctx.setIndexLanguage("en").setIndexText(ascii);
            return Mono.just(ctx.addStep(name(), "unknown->und; index(en) len=" + ascii.length()));
        }

        Map<com.github.pemistahl.lingua.api.Language, Double> confMap =
                detector.computeLanguageConfidenceValues(sample);
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

        // --- 7) build English-normalized index text (ASCII) ---
        String ascii = toAsciiIndex(sample);
        ctx.setIndexLanguage("en").setIndexText(ascii);

        // --- 8) log top-3 candidates for auditing ---
        String top3 = confMap.entrySet().stream()
                .sorted(Map.Entry.<com.github.pemistahl.lingua.api.Language, Double>comparingByValue().reversed())
                .limit(3)
                .map(e -> e.getKey().name().toLowerCase(Locale.ROOT) + ":" +
                        String.format(Locale.ROOT, "%.2f", e.getValue()))
                .collect(Collectors.joining(", "));

        return Mono.just(ctx.addStep(name(),
                "best=" + iso + " (" + String.format(Locale.ROOT, "%.2f", conf) + "); top3=" + top3 +
                        "; index(en) len=" + ascii.length()));
    }

    // --- helpers ---

    /** Build ASCII-only, lowercase, symbol-stripped index text from non-code sample. */
    private static String toAsciiIndex(String sample) {
        String translated = applyCustomTranslations(sample);
        String ascii = TO_ASCII.transliterate(translated);
        // keep letters/digits/space only, collapse spaces, lowercase
        ascii = ascii.replaceAll("[^A-Za-z0-9\\s]", " ");
        ascii = ascii.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return ascii;
    }

    private static String applyCustomTranslations(String sample) {
        if (sample == null || sample.isEmpty()) {
            return "";
        }
        String result = sample;
        for (ReplacementRule rule : CUSTOM_TRANSLATIONS) {
            result = rule.apply(result);
        }
        return result;
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
            ReplacementRule.literal("郭育奇高盛", "Yuqi Guo, Goldman Sachs"),
            ReplacementRule.literal("郭育奇，", "Yuqi Guo, "),
            ReplacementRule.literal("郭育奇、", "Yuqi Guo, "),
            ReplacementRule.literal("郭育奇。", "Yuqi Guo."),
            ReplacementRule.literal("郭育奇", "Yuqi Guo"),
            ReplacementRule.literal("高盛", "Goldman Sachs")
    );

    private record ReplacementRule(Pattern pattern, String replacement) {
        static ReplacementRule literal(String literal, String replacement) {
            return new ReplacementRule(Pattern.compile(Pattern.quote(literal)), replacement);
        }

        String apply(String input) {
            return pattern.matcher(input).replaceAll(replacement);
        }
    }
}
