package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.PromptTemplate;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class IntentClassifierProcessor implements TextProcessor {

    private static final String NAME = "intent-classifier";

    // 英文/数字 token + 连续的汉字串
    private static final Pattern TOKENIZER =
            Pattern.compile("([a-z0-9+.#-]+)|([\\p{IsHan}]+)");

    // 检测是否包含汉字
    private static final Pattern HAN_CHAR = Pattern.compile("[\\p{IsHan}]");

    // 简单的中英文招呼语（低成本 builtin 规则）
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

    // 关键字词典：canonical -> 所有同义词（含自己）
    private final Map<String, Set<String>> expandedLexicon;

    // 规则列表（可通过 json 扩展）
    private final List<Rule> rules;

    // 模板查找器（这里给一个内存版 demo，将来可替换成 ES / DB 查询）
    private final TemplateFinder templateFinder = new InMemoryTemplateFinder();

    public IntentClassifierProcessor(ResourceLoader resourceLoader) {
        this.expandedLexicon = loadLexicon(resourceLoader, "keywords_map.json");
        this.rules = loadRules(resourceLoader, "intent_rules.json");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Mono<ProcessingContext> process(ProcessingContext ctx) {
        // 这里按你的 pipeline 设计：优先用 indexText（经过清洗 + 翻译后的英文）
        final String text = Optional.ofNullable(ctx.getIndexText())
                .filter(s -> !s.isBlank())
                .orElseGet(() -> Optional.ofNullable(ctx.getNormalized()).orElse(""));

        if (text.isBlank()) {
            ctx.addStep(NAME, "empty text; keep UNKNOWN");
            ctx.setIntent(Intent.UNKNOWN);
            return Mono.just(ctx);
        }

        // 1）分词（中英文），并生成 tokenSet 便于规则匹配
        List<String> tokens = tokenize(text);
        Set<String> tokenSet = new HashSet<>(tokens);

        if (tokenSet.isEmpty()) {
            log.debug("[{}] tokenSet is empty after tokenize, text='{}'", NAME, text);
        }

        // 2）结合 keywords_map.json 做「规范化标签扩展」
        //    如果任一同义词命中，就把 canonical term 加到 tags 中
        Set<String> tags = new LinkedHashSet<>(Optional.ofNullable(ctx.getTags())
                .orElseGet(LinkedHashSet::new));

        expandedLexicon.forEach((canonical, synonyms) -> {
            if (synonyms.stream().anyMatch(tokenSet::contains)) {
                tags.add(canonical);
            }
        });

        // 2.1）基于 token + tags 生成 keywords 列表，给后面检索 / 模板查找用
        ctx.setKeywords(deriveKeywords(tokens, tags));

        // 3）规则打分，选出最佳意图
        Intent predicted = Intent.UNKNOWN;
        String matchedRule = null;
        int bestScore = Integer.MIN_VALUE;

        // 内置一个 greeting 特例，避免简单问好也走复杂规则
        if (isGreetingText(text)) {
            predicted = Intent.GREETING;
            matchedRule = "builtin:greeting";
        } else if (!rules.isEmpty()) {
            for (Rule r : rules) {
                int score = 0;

                // any 命中 +1
                for (String kw : r.any) {
                    if (hit(kw, tokenSet)) score += 1;
                }

                // all 全部命中，额外 +2
                boolean allOk = true;
                for (String kw : r.all) {
                    if (!hit(kw, tokenSet)) {
                        allOk = false;
                        break;
                    }
                }
                if (allOk && !r.all.isEmpty()) score += 2;

                // none 命中则 -2
                for (String kw : r.none) {
                    if (hit(kw, tokenSet)) score -= 2;
                }

                // tag 命中加分
                for (String t : r.tagsBoost) {
                    if (tags.contains(t)) score += 1;
                }

                if (score > bestScore && score >= r.minScore) {
                    bestScore = score;
                    predicted = r.intent;
                    matchedRule = r.debugName;
                }
            }
        }

        // 4）写回上下文
        ctx.setIntent(predicted);
        tags.add("intent:" + predicted.name().toLowerCase(Locale.ROOT));
        ctx.setTags(tags);

        // 5）可选：按 意图 + 语言 查找 prompt 模板（目前是内存 demo，可替换为 ES）
        Optional<PromptTemplate> tpl = templateFinder.findTopByIntentAndLanguage(
                predicted.name().toLowerCase(Locale.ROOT),
                Optional.ofNullable(ctx.getIndexLanguage()).orElse("en")
        );
        tpl.ifPresent(ctx::setTemplate);

        // 6）记录步骤日志
        String note = "intent=" + predicted +
                (matchedRule != null ? (", rule=" + matchedRule) : "") +
                (tpl.isPresent() ? (", template=" + tpl.get().getId()) : "") +
                ", tags=" + new ArrayList<>(tags);
        ctx.addStep(NAME, note);

        return Mono.just(ctx);
    }

    // ----------------------------------------------------
    // 分词逻辑：英文单词 + 汉字串 + 汉字 n-gram
    // ----------------------------------------------------
    private List<String> tokenize(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        String text = raw.toLowerCase(Locale.ROOT);
        Matcher m = TOKENIZER.matcher(text);

        List<String> words = new ArrayList<>();        // 英文/数字 token + 整段汉字串
        List<String> hanCharNgrams = new ArrayList<>(); // 汉字单字 + 双字 n-gram
        boolean matched = false;

        while (m.find()) {
            matched = true;
            String latin = m.group(1);
            String han = m.group(2);

            if (latin != null) {
                words.add(latin);
            } else if (han != null) {
                // 整段汉字串作为一个 token
                words.add(han);
                // 额外：单字 + 双字 n-gram，增加召回（不引入第三方中文分词）
                for (int i = 0; i < han.length(); i++) {
                    hanCharNgrams.add(han.substring(i, i + 1));
                }
                for (int i = 0; i + 1 < han.length(); i++) {
                    hanCharNgrams.add(han.substring(i, i + 2));
                }
            }
        }

        // 如果完全没有匹配到（例如只有标点），做一个兜底
        if (!matched) {
            if (HAN_CHAR.matcher(text).find()) {
                // 如果包含汉字，退化为「逐字」切分
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    String s = String.valueOf(c).trim();
                    if (!s.isEmpty() && HAN_CHAR.matcher(s).find()) {
                        words.add(s);
                    }
                }
            } else {
                // 否则按空格简单拆分英文
                for (String part : text.split("\\s+")) {
                    if (!part.isBlank()) {
                        words.add(part);
                    }
                }
            }
        }

        // 添加跨词 bigram，例如 "sign in", "credit card" 之类
        List<String> tokens = new ArrayList<>(words.size() * 2 + hanCharNgrams.size());
        tokens.addAll(words);
        for (int i = 1; i < words.size(); i++) {
            tokens.add(words.get(i - 1) + " " + words.get(i));
        }
        // 再加上汉字 n-gram
        tokens.addAll(hanCharNgrams);

        return tokens;
    }

    private boolean hit(String kw, Set<String> tokenSet) {
        if (tokenSet.contains(kw)) return true;
        Set<String> syn = expandedLexicon.get(kw);
        if (syn != null) {
            for (String s : syn) {
                if (tokenSet.contains(s)) return true;
            }
        }
        return false;
    }

    // 判断是否是简单的 greeting 文本
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
            // 保留英文字母、空白、以及中文「你 / 您 / 好」
            if ((c >= 'a' && c <= 'z') ||
                    Character.isWhitespace(c) ||
                    c == '\u4f60' || // 你
                    c == '\u597d' || // 好
                    c == '\u60a8'    // 您
            ) {
                cleaned.append(c);
            }
        }
        String normalized = cleaned.toString().replaceAll("\\s+", " ").trim();
        return GREETING_PHRASES.contains(normalized);
    }

    // 基于 tokens + tags 构造关键词列表（顺序稳定，最多 20 个）
    private List<String> deriveKeywords(List<String> tokens, Set<String> tags) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();

        // 先按分词顺序加入（LinkedHashSet 自动去重）
        for (String token : tokens) {
            if (token == null) continue;
            String t = token.trim();
            if (t.isEmpty()) continue;
            // 英文长度 <2 的噪音丢弃，但单个汉字允许
            if (t.length() < 2 && !HAN_CHAR.matcher(t).find()) continue;
            ordered.add(t);
        }

        // 再把 tag 也合并进来（例如 intent:xxx、lexicon canonical term）
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            ordered.add(tag.toLowerCase(Locale.ROOT));
        }

        return ordered.stream()
                .limit(20)
                .toList();
    }

    // ----------------------------------------------------
    // 规则 & 词典加载
    // ----------------------------------------------------
    private List<Rule> loadRules(ResourceLoader rl, String classpathName) {
        try (InputStream in = openForRead(
                rl,
                classpathName,
                "intent.rules",       // -Dintent.rules=...
                "INTENT_RULES_PATH",  // env INTENT_RULES_PATH=...
                "src/main/resources/" + classpathName
        )) {
            if (in == null) {
                log.info("[{}] No intent rules found for {}", NAME, classpathName);
                return Collections.emptyList();
            }
            RuleBundle rb = om.readValue(in, RuleBundle.class);
            return toRules(rb);
        } catch (Exception e) {
            log.warn("[{}] Failed to load intent rules {} – {}", NAME, classpathName, e.toString());
            return Collections.emptyList();
        }
    }

    private Map<String, Set<String>> loadLexicon(ResourceLoader rl, String classpathName) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        try (InputStream in = openForRead(
                rl,
                classpathName,
                "keywords.map.path",  // -Dkeywords.map.path=...
                "KEYWORDS_MAP_PATH",  // env KEYWORDS_MAP_PATH=...
                "src/main/resources/" + classpathName
        )) {
            if (in == null) {
                log.info("[{}] No lexicon found for {}", NAME, classpathName);
                return out;
            }
            LexiconBundle bundle = om.readValue(in, LexiconBundle.class);
            if (bundle.terms != null) {
                for (Map.Entry<String, TermEntry> e : bundle.terms.entrySet()) {
                    String canonical = e.getKey().toLowerCase(Locale.ROOT);
                    Set<String> syns = new LinkedHashSet<>();
                    // canonical 自己也作为一个同义词
                    syns.add(canonical);
                    if (e.getValue() != null) {
                        if (e.getValue().D != null) {
                            e.getValue().D.forEach(s -> addLower(syns, s));
                        }
                        if (e.getValue().C != null) {
                            e.getValue().C.forEach(s -> addLower(syns, s));
                        }
                    }
                    out.put(canonical, syns);
                }
            }
            log.info("[{}] Loaded lexicon {}, terms={}", NAME, classpathName, out.size());
            return out;
        } catch (Exception e) {
            log.warn("[{}] Failed to load lexicon {} – {}", NAME, classpathName, e.toString());
            return out;
        }
    }

    /**
     * 统一的资源打开逻辑，按顺序尝试：
     * 1）Spring ResourceLoader classpath:
     * 2）ClassLoader#getResourceAsStream
     * 3）系统属性 -D{sysPropKey}
     * 4）环境变量 {envKey}
     * 5）最后兜底的文件路径（IDE 直接跑时有用）
     */
    private InputStream openForRead(ResourceLoader rl,
                                    String classpathName,
                                    String sysPropKey,
                                    String envKey,
                                    String lastResortPath) {
        try {
            Resource r = rl.getResource("classpath:" + classpathName);
            if (r.exists()) return r.getInputStream();
        } catch (Exception ignore) {
        }

        try {
            InputStream in = IntentClassifierProcessor.class
                    .getClassLoader()
                    .getResourceAsStream(classpathName);
            if (in != null) return in;
        } catch (Exception ignore) {
        }

        try {
            String external = Optional.ofNullable(System.getProperty(sysPropKey))
                    .orElse(System.getenv(envKey));
            if (external != null && !external.isBlank()) {
                File f = new File(external);
                if (f.exists() && f.isFile()) return new FileInputStream(f);
            }
        } catch (Exception ignore) {
        }

        try {
            File f = new File(lastResortPath);
            if (f.exists() && f.isFile()) return new FileInputStream(f);
        } catch (Exception ignore) {
        }

        return null;
    }

    private static void addLower(Set<String> set, String s) {
        if (s != null && !s.isBlank()) {
            set.add(s.toLowerCase(Locale.ROOT));
        }
    }

    private List<Rule> toRules(RuleBundle bundle) {
        if (bundle == null || bundle.rules == null) return Collections.emptyList();
        List<Rule> rs = new ArrayList<>(bundle.rules.size());
        for (RuleJson rj : bundle.rules) {
            String intentName = (rj.intent == null)
                    ? "OTHER"
                    : rj.intent.trim().toUpperCase(Locale.ROOT);
            Intent intent;
            try {
                intent = Intent.valueOf(intentName);
            } catch (IllegalArgumentException ex) {
                intent = Intent.UNKNOWN;
            }
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
        log.info("[{}] Loaded {} intent rules", NAME, rs.size());
        return rs;
    }

    private static List<String> toLowerList(List<String> in) {
        if (in == null) return List.of();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    // ---------------- JSON 映射类 ----------------
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RuleBundle {
        public List<RuleJson> rules;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RuleJson {
        public String name;       // 规则调试名，可选
        public String intent;     // 例如 "LOGIN"
        public Integer minScore;  // 默认 1
        public List<String> any;
        public List<String> all;
        public List<String> none;
        public List<String> tagsBoost;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LexiconBundle {
        public Map<String, TermEntry> terms;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TermEntry {
        public List<String> D; // dataset 自动抽取的词
        public List<String> C; // 手工/精修的上下文同义词
    }

    // 内部规则结构（运行时用这个）
    @Data
    @AllArgsConstructor
    private static class Rule {
        final Intent intent;
        final int minScore;
        final List<String> any;
        final List<String> all;
        final List<String> none;
        final List<String> tagsBoost;
        final String debugName;
    }

    // ----------------------------------------------------
    // 模板查找（这里是 demo，可以将来替换为 ES / DB 实现）
    // ----------------------------------------------------
    interface TemplateFinder {
        Optional<PromptTemplate> findTopByIntentAndLanguage(String intent, String lang);
    }

    /**
     * 内存版模板查找器：
     * - 仅做示例：意图 "chatbot" + 语言 "en" 时，返回一个固定模板
     * - 你以后可以在这里改成：从 Elasticsearch / Postgres 里查 PromptTemplate
     */
    static class InMemoryTemplateFinder implements TemplateFinder {
        private final Map<String, PromptTemplate> mem = new HashMap<>();

        InMemoryTemplateFinder() {
            PromptTemplate demo = new PromptTemplate();
            demo.setId("demo-chatbot-en");
            demo.setLanguage("en");
            demo.setIntent("chatbot");
            demo.setSystem("You are an assistant that answers based only on customer dataset.");
            demo.setUserTemplate("User said: {{input}}");
            mem.put("chatbot#en", demo);
        }

        @Override
        public Optional<PromptTemplate> findTopByIntentAndLanguage(String intent, String lang) {
            return Optional.ofNullable(mem.get(intent + "#" + lang));
        }
    }
}
