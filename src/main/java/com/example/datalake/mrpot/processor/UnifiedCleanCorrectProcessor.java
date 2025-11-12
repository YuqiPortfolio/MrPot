package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.util.CodeFenceUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UnifiedCleanCorrectProcessor implements TextProcessor {
    @Override public String name() { return "unified-clean-correct"; }

    // ====== Precompiled patterns (fast path) ======
    private static final Pattern SENT_SPLIT = Pattern.compile("[\\n；;。.!?]+\\s*");
    private static final Pattern CN_EXCESS_SPACES = Pattern.compile("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})");
    private static final Pattern CN_WORD_SPACE = Pattern.compile("(?<=\\p{IsHan})\\s+(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])\\s+(?=\\p{IsHan})");
    private static final Pattern CN_REPEAT_CHAR = Pattern.compile("([\\p{IsHan}！？。；，、])\\1{1,}");
    private static final Pattern SENTENCE_START_I = Pattern.compile("(?m)(^|[\\n\\.!?]\\s*)i\\b");

    @Override
    public Mono<ProcessingContext> process(ProcessingContext ctx) {
        String raw = ctx.getRawInput();
        if (raw == null) raw = "";

        // 0) Code-fence aware segmentation (skip corrections inside fences)
        List<CodeFenceUtils.Segment> segs = CodeFenceUtils.split(raw);
        List<CodeFenceUtils.Segment> out = new ArrayList<>(segs.size());

        // Outline buckets
        Map<String, List<String>> outline = new LinkedHashMap<>();
        outline.put("TASKS", new ArrayList<>());
        outline.put("CONSTRAINTS", new ArrayList<>());
        outline.put("CONTEXT", new ArrayList<>());
        outline.put("OUTPUT", new ArrayList<>());

        for (CodeFenceUtils.Segment seg : segs) {
            if (seg.isCode) { // never touch code
                out.add(seg);
                continue;
            }
            String t = seg.text;

            // 1) Normalization (lossless; unify punctuation/whitespace)
            t = normalizeText(t);

            // 2) Light rule-based fixes
            t = applyLightRules(t);

            // 3) Classify sentences into outline buckets
            classifyToOutline(t, outline);

            out.add(new CodeFenceUtils.Segment(t, false));
        }

        // 4) Cross-segment de-dup & condensation + collapse blank lines
        String joined = CodeFenceUtils.join(out);
        String condensed = dedupAndCondense(joined);

        // 4.1) Post-format short plain text (e.g., "Hello\n\nworld" → "Hello world")
        condensed = postFormatShortPlainText(condensed);

        // 5) Length control
        int limit = Math.max(2000, Math.min(ctx.getCharLimit(), 8000));
        if (condensed.length() > limit) {
            condensed = condensed.substring(0, limit - 50) + "\n[Content condensed to enforce length limit]";
        }

        // 6) Write back
        double changeRatio = raw.isEmpty() ? 0.0 :
                Math.abs(condensed.length() - raw.length()) / (double) raw.length();

        ctx.setNormalized(condensed);
        ctx.setCorrected(condensed);
        ctx.setOutline(outline);
        ctx.setChangeRatio(changeRatio);

        return Mono.just(ctx.addStep(name(), "ratio=" + String.format(Locale.ROOT, "%.3f", changeRatio)));
    }

    // ========= Implementation details =========

    // Normalization: "lossless" / low risk
    private static String normalizeText(String s) {
        if (s == null || s.isEmpty()) return "";
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);
        s = s.replace("\uFEFF","").replace("\u200B","").replace("\u200C","")
                .replace("\u200D","").replace("\u200E","").replace("\u200F","");
        s = s.replaceAll("[\\p{Cntrl}&&[^\n\t]]", ""); // keep \n and \t
        s = s.replaceAll("[“”]", "\"").replaceAll("[‘’]", "'")
                .replace("—","-").replace("–","-");

        // Collapse repeats (ASCII + CJK)
        s = s.replaceAll("\\?{2,}", "?")
                .replaceAll("!{2,}", "!")
                .replaceAll("。{2,}", "。")
                .replaceAll("\\.{3,}", ".")
                .replaceAll("，{2,}", "，")
                .replaceAll(",{2,}", ",");

        s = s.replace("\r\n","\n").replace('\r','\n');
        s = s.replaceAll("[ \\t]{2,}", " ").trim();
        return s;
    }

    // Light rules: sentence-start 'i' → 'I', Chinese spacing cleanup
    private static String applyLightRules(String t) {
        // Sentence-start i → I
        Matcher si = SENTENCE_START_I.matcher(t);
        StringBuffer sbI = new StringBuffer();
        while (si.find()) {
            String g = si.group();
            si.appendReplacement(sbI, Matcher.quoteReplacement(g.replace("i", "I")));
        }
        si.appendTail(sbI);
        t = sbI.toString();

        // Chinese/Latin spacing & repeated punctuation
        t = CN_EXCESS_SPACES.matcher(t).replaceAll("");
        t = CN_WORD_SPACE.matcher(t).replaceAll("");
        t = CN_REPEAT_CHAR.matcher(t).replaceAll("$1");
        return t;
    }

    // Sentence classification → Outline buckets
    private static void classifyToOutline(String t, Map<String,List<String>> outline){
        String[] parts = SENT_SPLIT.split(t.trim());
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (s.isEmpty()) continue;
            String lower = s.toLowerCase(Locale.ROOT);

            if (lower.matches("^(please|help|write|实现|编写|生成|比较|分析|给我|需要)\\b.*")) {
                outline.get("TASKS").add(s);
            } else if (lower.matches(".*(must|should|不要|必须|仅|禁止|不可|不能|不允许).*")) {
                outline.get("CONSTRAINTS").add(s);
            } else if (lower.matches(".*(output|格式|schema|返回|字段|以.*格式|结构化).*")) {
                outline.get("OUTPUT").add(s);
            } else {
                outline.get("CONTEXT").add(s);
            }
        }
    }

    // De-duplication & condensation across lines
    private static String dedupAndCondense(String joined){
        String[] lines = joined.split("\\n");
        Set<String> seen = new HashSet<>(lines.length * 2);
        StringBuilder sb = new StringBuilder(joined.length());
        int blanks=0;
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) { if (++blanks<=1) sb.append('\n'); continue; }
            blanks=0;
            String key = l.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);
            // De-bounce consecutive duplicated words
            l = l.replaceAll("\\b(\\w{2,})\\s+\\1\\b", "$1");
            sb.append(l).append('\n');
        }
        return sb.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    // Post-format short plain text into a single friendly line
    private static String postFormatShortPlainText(String s) {
        if (s.isEmpty() || s.contains("```")) return s; // never touch code
        String[] rawLines = s.split("\\n");
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String rl : rawLines) {
            String t = rl.trim();
            if (!t.isEmpty()) lines.add(t);
        }
        if (lines.isEmpty() || lines.size() > 4) return s;

        // If any line has punctuation other than spaces, bail out
        for (String ln : lines) {
            if (ln.matches(".*[\\p{Punct}，。！？；、：‘’“”`~@#%^&*()\\[\\]{}<>/\\\\].*")) return s;
        }

        String merged = String.join(" ", lines).replaceAll("\\s{2,}", " ").trim();
        if (merged.isEmpty()) return s;

        char first = merged.charAt(0);
        if (Character.isLetter(first)) merged = Character.toUpperCase(first) + merged.substring(1);
        return merged;
    }
}
