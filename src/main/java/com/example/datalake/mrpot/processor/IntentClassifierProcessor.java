package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.PromptTemplate;
import com.example.datalake.mrpot.dao.KeywordsLexiconDao;
import com.example.datalake.mrpot.dao.IntentRulesDao;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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

    // 中文代词/语气词 + 一些英文代词，避免被当成关键词
    private static final Set<String> STOPWORDS = Set.of(
            "他", "她", "你", "我", "它", "我们", "你们", "他们", "她们", "它们",
            "吗", "呢", "啊", "吧", "了", "的",
            "he", "she", "you", "i", "we", "they", "it", "me", "him", "her", "them", "us"
    );

    private final KeywordsLexiconDao keywordsLexiconDao;
    private final IntentRulesDao intentRulesDao;

    // 模板查找器（这里给一个内存版 demo，将来可替换成 ES / DB 查询）
    private final TemplateFinder templateFinder = new InMemoryTemplateFinder();

    public IntentClassifierProcessor(KeywordsLexiconDao keywordsLexiconDao,
                                     IntentRulesDao intentRulesDao) {
        this.keywordsLexiconDao = keywordsLexiconDao;
        this.intentRulesDao = intentRulesDao;
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

        // 2）结合词典动态查询做「规范化标签扩展」
        Set<String> tags = new LinkedHashSet<>(Optional.ofNullable(ctx.getTags())
                .orElseGet(LinkedHashSet::new));
        addCanonicalTags(tokenSet, tags);

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
        } else {
            List<Rule> rules = loadRules(tokenSet);
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
                ", tags=" + new ArrayList<>(tags) +
                ", keywords=" + ctx.getKeywords();
        ctx.addStep(NAME, note);

        return Mono.just(ctx);
    }

    // ----------------------------------------------------
    // 分词逻辑：英文单词 + 汉字串 + 汉字 uni/bi/tri-gram
    // ----------------------------------------------------
    private List<String> tokenize(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        String text = raw.toLowerCase(Locale.ROOT);
        Matcher m = TOKENIZER.matcher(text);

        List<String> words = new ArrayList<>();         // 英文/数字 token + 整段汉字串
        List<String> hanCharNgrams = new ArrayList<>(); // 汉字 uni/bi/tri-gram
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
                // 单字
                for (int i = 0; i < han.length(); i++) {
                    hanCharNgrams.add(han.substring(i, i + 1));
                }
                // bigram
                for (int i = 0; i + 1 < han.length(); i++) {
                    hanCharNgrams.add(han.substring(i, i + 2));
                }
                // trigram：例如 "芝加" + "加哥" → "芝加哥"
                for (int i = 0; i + 2 < han.length(); i++) {
                    hanCharNgrams.add(han.substring(i, i + 3));
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
        // 再加上汉字 uni/bi/tri-gram
        tokens.addAll(hanCharNgrams);

        return tokens;
    }

    private void addCanonicalTags(Set<String> tokenSet, Set<String> tags) {
        if (keywordsLexiconDao == null) {
            return;
        }

        for (String token : tokenSet) {
            for (String canonical : keywordsLexiconDao.findCanonicalsByToken(token)) {
                tags.add(canonical.toLowerCase(Locale.ROOT));
            }
        }
    }

    private boolean hit(String kw, Set<String> tokenSet) {
        return tokenSet.contains(kw);
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

    /**
     * 过滤一个候选关键词：
     *  - 去掉空字符串
     *  - 去掉常见代词/语气词（STOPWORDS）
     *  - 英文 token：长度 < 3 丢弃
     *  - 汉字 token：长度 < 2 丢弃
     */
    private boolean isGoodKeyword(String kw) {
        if (kw == null) return false;
        String t = kw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return false;
        if (STOPWORDS.contains(t)) return false;

        boolean hasHan = HAN_CHAR.matcher(t).find();
        if (hasHan) {
            // 汉字长度 < 2 的丢弃
            if (t.length() < 2) return false;
        } else {
            // 英文长度 < 3 的丢弃
            if (t.length() < 3) return false;
        }
        return true;
    }

    // 基于 keywords_map.json + tokens/tags 抽取关键词：
    // 1) 只返回 lexicon 里的 canonical term
    // 2) 按长度降序排序
    // 3) 最多保留前 5 个
    private List<String> deriveKeywords(List<String> tokens, Set<String> tags) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }

        Set<String> tokenSet = new HashSet<>();
        for (String t : tokens) {
            if (t != null && !t.isBlank()) {
                tokenSet.add(t.toLowerCase(Locale.ROOT));
            }
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        // 1) 按 lexicon 检索：命中任一 token → 取 canonical
        if (keywordsLexiconDao != null) {
            for (String token : tokenSet) {
                for (String canonical : keywordsLexiconDao.findCanonicalsByToken(token)) {
                    if (isGoodKeyword(canonical)) {
                        candidates.add(canonical);
                    }
                }
            }
        }

        // 2) 从 tags 中补充：如果某个 tag 恰好就是 lexicon canonical，也可视作关键词
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            String t = tag.toLowerCase(Locale.ROOT);
            if (isGoodKeyword(t)) {
                candidates.add(t);
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        // 3) 按长度降序排序（长词优先，例如“芝加哥”优于“芝加”）
        List<String> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> {
            int la = a.length();
            int lb = b.length();
            if (la != lb) {
                return Integer.compare(lb, la); // 长的在前
            }
            return a.compareTo(b);
        });

        // 4) 最多取前 5 个
        return sorted.stream()
                .limit(5)
                .toList();
    }

    // ----------------------------------------------------
    // 规则 & 词典加载
    // ----------------------------------------------------
    private List<Rule> loadRules(Set<String> tokenSet) {
        if (intentRulesDao == null || tokenSet.isEmpty()) {
            return Collections.emptyList();
        }

        List<RuleJson> ruleJsons = new ArrayList<>();
        for (IntentRulesDao.IntentRuleEntry entry : intentRulesDao.findActiveRulesByTokens(tokenSet)) {
            if (entry == null) {
                continue;
            }

            String canonical = Optional.ofNullable(entry.canonical())
                    .map(s -> s.trim().toUpperCase(Locale.ROOT))
                    .orElse(null);
            if (canonical == null || canonical.isBlank()) {
                continue;
            }

            List<String> any = new ArrayList<>();
            addLower(any, canonical);
            if (entry.synonyms() != null) {
                for (String s : entry.synonyms()) {
                    addLower(any, s);
                }
            }

            if (any.isEmpty()) {
                continue;
            }

            RuleJson rj = new RuleJson();
            rj.intent = canonical;
            rj.minScore = 1;
            rj.any = any;
            rj.all = List.of();
            rj.none = List.of();
            rj.tagsBoost = List.of();
            rj.name = canonical.toLowerCase(Locale.ROOT);
            ruleJsons.add(rj);
        }

        RuleBundle bundle = new RuleBundle();
        bundle.rules = ruleJsons;
        return toRules(bundle);
    }

    private static void addLower(Set<String> set, String s) {
        if (s != null && !s.isBlank()) {
            set.add(s.toLowerCase(Locale.ROOT));
        }
    }

    private static void addLower(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value.toLowerCase(Locale.ROOT));
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
