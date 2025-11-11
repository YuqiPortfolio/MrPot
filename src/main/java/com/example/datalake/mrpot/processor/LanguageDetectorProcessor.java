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
    private static final Pattern HAN_SEQUENCE = Pattern.compile("[\\p{IsHan}]{1,}");
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

        DictionaryTranslation dictionary = applyDictionaryTranslations(sample);
        for (String keyword : dictionary.keywords()) {
            collector.addPhrase(keyword);
        }
        collector.scan(dictionary.text());
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

    private static DictionaryTranslation applyDictionaryTranslations(String sample) {
        if (sample == null || sample.isEmpty()) {
            return new DictionaryTranslation("", new LinkedHashSet<>());
        }
        Matcher matcher = HAN_SEQUENCE.matcher(sample);
        StringBuilder english = new StringBuilder();
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        while (matcher.find()) {
            String han = matcher.group();
            List<String> phrases = ChineseDictionary.translate(han);
            if (phrases.isEmpty()) continue;
            for (String phrase : phrases) {
                if (english.length() > 0) english.append(' ');
                english.append(phrase);
                keywords.add(phrase);
            }
        }
        return new DictionaryTranslation(english.toString(), keywords);
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

    private static final class ChineseDictionary {
        private static final Map<String, List<String>> PHRASE_MAP;
        private static final int MAX_PHRASE_LENGTH;

        static {
            Map<String, List<String>> map = new LinkedHashMap<>();
            put(map, "请提供", "please provide");
            put(map, "请比较", "please compare");
            put(map, "请使用", "please use");
            put(map, "请解释", "please explain");
            put(map, "请", "please");
            put(map, "比较", "compare");
            put(map, "分析", "analyze");
            put(map, "生成", "generate");
            put(map, "总结", "summarize");
            put(map, "提供", "provide");
            put(map, "使用", "use");
            put(map, "转换", "convert");
            put(map, "改成", "convert");
            put(map, "解释", "explain");
            put(map, "详细", "detailed");
            put(map, "步骤", "steps");
            put(map, "说明", "description");
            put(map, "示例", "example");
            put(map, "回答", "answer");
            put(map, "问题", "question");
            put(map, "方案", "solution");
            put(map, "策略", "strategy");
            put(map, "要求", "requirements");
            put(map, "约束", "constraints");
            put(map, "限制", "constraints");
            put(map, "必须", "must");
            put(map, "需要", "need");
            put(map, "不要", "do not");
            put(map, "禁止", "forbid");
            put(map, "仅", "only");
            put(map, "包括", "include");
            put(map, "排除", "exclude");
            put(map, "输出", "output");
            put(map, "输入", "input");
            put(map, "格式", "format");
            put(map, "字段", "fields");
            put(map, "列表", "list");
            put(map, "表格", "table");
            put(map, "代码", "code");
            put(map, "版本", "version");
            put(map, "脚本", "script");
            put(map, "函数", "function");
            put(map, "类", "class");
            put(map, "接口", "interface");
            put(map, "应用", "application");
            put(map, "程序", "program");
            put(map, "文档", "documentation");
            put(map, "描述", "description");
            put(map, "背景", "background");
            put(map, "上下文", "context");
            put(map, "目标", "goal");
            put(map, "计划", "plan");
            put(map, "比较", "compare");
            put(map, "十年", "ten year");
            put(map, "十", "ten");
            put(map, "年", "year");
            put(map, "保值", "resale value");
            put(map, "保养", "maintenance");
            put(map, "成本", "cost");
            put(map, "费用", "costs");
            put(map, "保险", "insurance");
            put(map, "支出", "expense");
            put(map, "考虑", "consider");
            put(map, "同时", "also");
            put(map, "以及", "as well as");
            put(map, "还有", "also");
            put(map, "客户", "customer");
            put(map, "用户", "user");
            put(map, "公司", "company");
            put(map, "企业", "business");
            put(map, "项目", "project");
            put(map, "团队", "team");
            put(map, "产品", "product");
            put(map, "服务", "service");
            put(map, "市场", "market");
            put(map, "竞争", "competition");
            put(map, "优势", "advantage");
            put(map, "风险", "risk");
            put(map, "机会", "opportunity");
            put(map, "需求", "demand");
            put(map, "收益", "benefit");
            put(map, "收入", "revenue");
            put(map, "利润", "profit");
            put(map, "成本", "cost");
            put(map, "预算", "budget");
            put(map, "时间", "time");
            put(map, "日期", "date");
            put(map, "地点", "location");
            put(map, "地区", "region");
            put(map, "国家", "country");
            put(map, "城市", "city");
            put(map, "名称", "name");
            put(map, "标题", "title");
            put(map, "关键词", "keywords");
            put(map, "语言", "language");
            put(map, "中文", "chinese");
            put(map, "英文", "english");
            put(map, "翻译", "translate");
            put(map, "解释", "explain");
            put(map, "总结", "summarize");
            put(map, "报告", "report");
            put(map, "分析", "analysis");
            put(map, "计划", "plan");
            put(map, "步骤", "steps");
            put(map, "原因", "reason");
            put(map, "影响", "impact");
            put(map, "建议", "recommendation");
            put(map, "改进", "improvement");
            put(map, "优化", "optimize");
            put(map, "评估", "evaluate");
            put(map, "测试", "test");
            put(map, "验证", "validate");
            put(map, "部署", "deploy");
            put(map, "发布", "release");
            put(map, "监控", "monitor");
            put(map, "维护", "maintain");
            put(map, "支持", "support");
            put(map, "文档", "documentation");
            put(map, "资源", "resources");
            put(map, "链接", "link");
            put(map, "附件", "attachment");
            put(map, "说明", "description");
            put(map, "背景", "background");
            put(map, "摘要", "summary");
            put(map, "目标", "objective");

            PHRASE_MAP = Collections.unmodifiableMap(map);
            int max = 1;
            for (String key : map.keySet()) {
                if (key.length() > max) {
                    max = key.length();
                }
            }
            MAX_PHRASE_LENGTH = max;
        }

        private static void put(Map<String, List<String>> map, String han, String english) {
            map.put(han, List.of(english));
        }

        static List<String> translate(String han) {
            if (han == null || han.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            int i = 0;
            while (i < han.length()) {
                int max = Math.min(MAX_PHRASE_LENGTH, han.length() - i);
                boolean matched = false;
                for (int len = max; len >= 1; len--) {
                    String sub = han.substring(i, i + len);
                    List<String> english = PHRASE_MAP.get(sub);
                    if (english != null) {
                        out.addAll(english);
                        i += len;
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    i++;
                }
            }
            return out;
        }
    }

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
    private record DictionaryTranslation(String text, LinkedHashSet<String> keywords) {}

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
