package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbDocument;
import com.example.datalake.mrpot.model.KbSnippet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 基于 kb_documents 表的 KB 检索实现：
 * - 先用 query + keywords 在 content 上做 ILIKE 搜索
 * - 如果没有命中，则 fallback 拿最近几篇文档
 * - 在 Java 侧做「片段提取」+ 全局字符预算控制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupabaseKbSearchService implements KbSearchService {

    // 候选文档最大数量
    private static final int MAX_DOC_CANDIDATES = 20;

    // 每篇文档最多给多少字符的 snippet（局部预算）
    // 改小一点，让 [DOC n] 更精简
    private static final int MAX_SNIPPET_PER_DOC = 180;
    private static final int MIN_KEYWORD_LEN = 2;
    private static final int MAX_SENTENCES_PER_SNIPPET = 3;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public List<KbSnippet> searchSnippets(String query,
                                          List<String> keywords,
                                          int maxSnippets,
                                          int maxTotalChars) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        log.debug("SupabaseKbSearchService.searchSnippets query='{}', keywords={}, maxSnippets={}, maxTotalChars={}",
                query, keywords, maxSnippets, maxTotalChars);

        List<String> normalizedKeywords = normalizeKeywords(query, keywords);

        int docLimit = Math.min(
                MAX_DOC_CANDIDATES,
                Math.max(maxSnippets * 2, maxSnippets)
        );

        // 1) 用 query + keywords 查候选文档
        List<KbDocument> docs = searchCandidates(query, normalizedKeywords, docLimit);

        // 2) 如果还空，做 fallback：拿最近几篇文档兜底
        if (docs.isEmpty()) {
            log.debug("No kb_documents matched query/keywords, fallback by latest docs");
            docs = fetchLatestDocuments(docLimit);
        }

        if (docs.isEmpty()) {
            return Collections.emptyList();
        }

        // 3) 在「总预算」内从这些文档中抽片段
        int remaining = maxTotalChars;
        List<KbSnippet> result = new ArrayList<>();

        for (KbDocument doc : docs) {
            if (doc == null) continue;
            if (remaining <= 0) break;
            if (result.size() >= maxSnippets) break;

            String content = safe(doc.getContent());
            if (content.isBlank()) continue;

            int perDocBudget = Math.min(MAX_SNIPPET_PER_DOC, remaining);
            if (perDocBudget <= 0) break;

            String snippetText = extractSnippetFromContent(content, normalizedKeywords, perDocBudget);
            snippetText = normalizeWhitespace(snippetText);
            if (snippetText.isBlank()) continue; // 如果没有关键词命中，直接跳过

            KbSnippet snippet = KbSnippet.builder()
                    .docId(doc.getId())
                    .title(shortTitle(doc))
                    .source(shortSource(doc))
                    .snippet(snippetText)
                    .score(0.0)
                    .build();

            result.add(snippet);
            remaining -= snippetText.length();
        }

        return result;
    }

    private List<String> normalizeKeywords(String query, List<String> keywords) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (keywords != null) {
            for (String kw : keywords) {
                if (kw == null) continue;
                String trimmed = kw.trim();
                if (!trimmed.isEmpty()) {
                    unique.add(trimmed);
                }
            }
        }

        if (query != null && !query.isBlank()) {
            for (String token : query.trim().split("\\s+")) {
                if (token.length() < 2) continue; // 太短的词命中效果差，忽略
                unique.add(token);
            }
        }

        return new ArrayList<>(unique);
    }

    /**
     * 用 query + keywords 在 content 上做 ILIKE 搜索。
     */
    private List<KbDocument> searchCandidates(String query,
                                              List<String> keywords,
                                              int limit) {
        String trimmedQuery = query.trim();
        // 避免整段太长，截一截再做 ILIKE
        if (trimmedQuery.length() > 128) {
            trimmedQuery = trimmedQuery.substring(0, 128);
        }

        StringBuilder sql = new StringBuilder("""
                SELECT id, doc_type, content, metadata
                FROM kb_documents
                WHERE 1=1
                """);

        Map<String, Object> params = new HashMap<>();

        // (content ILIKE query OR content ILIKE kw1 OR ...)
        sql.append(" AND ( content ILIKE :q ");
        params.put("q", "%" + trimmedQuery + "%");

        StringBuilder scoreExpr = new StringBuilder("CASE WHEN content ILIKE :q THEN 2 ELSE 0 END");

        int kwIndex = 0;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String key = "kw" + kwIndex++;
            sql.append(" OR content ILIKE :").append(key).append(" ");
            scoreExpr.append(" + CASE WHEN content ILIKE :").append(key).append(" THEN 1 ELSE 0 END");
            params.put(key, "%" + kw.trim() + "%");
        }
        sql.append(") ");

        sql.append(" ORDER BY (").append(scoreExpr).append(") DESC, id DESC LIMIT :limit");
        params.put("limit", limit);

        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> {
            KbDocument d = new KbDocument();
            d.setId(rs.getLong("id"));
            d.setDocType(rs.getString("doc_type"));
            d.setContent(rs.getString("content"));
            d.setMetadata(rs.getString("metadata"));
            return d;
        });
    }

    /**
     * fallback：查最近的几篇文档。
     */
    private List<KbDocument> fetchLatestDocuments(int limit) {
        String sql = """
                SELECT id, doc_type, content, metadata
                FROM kb_documents
                ORDER BY id DESC
                LIMIT :limit
                """;

        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            KbDocument d = new KbDocument();
            d.setId(rs.getLong("id"));
            d.setDocType(rs.getString("doc_type"));
            d.setContent(rs.getString("content"));
            d.setMetadata(rs.getString("metadata"));
            return d;
        });
    }

    /**
     * 从整篇 content 中抽一个 snippet：
     * - 仅当至少有一个关键词在 content 中命中时才返回片段；
     * - 句子级切分 + 评分，优先保留覆盖关键词的短句，最多取少量句子；
     * - 超预算时再按句子边界柔和截断。
     */
    private String extractSnippetFromContent(String content,
                                             List<String> keywords,
                                             int maxChars) {
        if (content == null) return "";
        String text = content.strip();
        if (text.isEmpty()) return "";
        if (maxChars <= 0) return "";

        List<String> normalizedKeywords = normalizeKeywordsForMatching(keywords);
        if (normalizedKeywords.isEmpty()) {
            // 要求：如果没有 keywords，直接不返回 snippet
            return "";
        }

        List<SentenceSpan> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return "";
        }

        List<ScoredSentence> scored = new ArrayList<>();
        for (SentenceSpan s : sentences) {
            ScoredSentence scoredSentence = scoreSentence(s, normalizedKeywords);
            if (scoredSentence != null) {
                scored.add(scoredSentence);
            }
        }

        if (scored.isEmpty()) {
            // 没有关键词命中的句子，直接放弃
            return "";
        }

        // 高分句优先，分数一致时按原文位置排序
        scored.sort((a, b) -> {
            int cmp = Integer.compare(b.score, a.score);
            if (cmp != 0) return cmp;
            return Integer.compare(a.span.start, b.span.start);
        });

        StringBuilder snippet = new StringBuilder();
        Set<String> coveredKeywords = new HashSet<>();
        List<SentenceSpan> picked = new ArrayList<>(MAX_SENTENCES_PER_SNIPPET);

        for (ScoredSentence candidate : scored) {
            if (picked.size() >= MAX_SENTENCES_PER_SNIPPET) break;

            boolean improvesCoverage = !coveredKeywords.containsAll(candidate.matchedKeywords);
            boolean isTopSentence = picked.isEmpty();

            if (isTopSentence || improvesCoverage) {
                picked.add(candidate.span);
                coveredKeywords.addAll(candidate.matchedKeywords);
            }
        }

        if (picked.isEmpty()) {
            return "";
        }

        // 保持原始顺序，便于阅读
        picked.sort(Comparator.comparingInt(span -> span.start));

        for (SentenceSpan span : picked) {
            if (snippet.length() > 0) {
                snippet.append(' ');
            }
            String clipped = clipToSentenceBoundary(span.text, maxChars - snippet.length());
            snippet.append(clipped);
            if (snippet.length() >= maxChars) {
                break;
            }
        }

        String clipped = clipToSentenceBoundary(snippet.toString(), maxChars);
        return clipped;
    }

    /**
     * 将 snippet 截到最近的句子边界内，避免过长。
     */
    private static String clipToSentenceBoundary(String snippet, int maxChars) {
        if (snippet == null) return "";
        String s = snippet.strip();
        if (s.length() <= maxChars) return s;

        String sub = s.substring(0, maxChars);
        int cut = lastSentenceBoundary(sub);
        if (cut > 40) { // 避免截得太短
            sub = sub.substring(0, cut);
        }
        return sub.strip() + "...";
    }

    /**
     * 寻找最后一个句子结束符号的位置。
     */
    private static int lastSentenceBoundary(String text) {
        if (text == null || text.isEmpty()) return -1;
        int last = -1;
        char[] marks = {'.', '?', '!', '。', '？', '！', '\n'};
        for (char m : marks) {
            int idx = text.lastIndexOf(m);
            if (idx > last) last = idx;
        }
        return (last == -1) ? -1 : last + 1;
    }

    private static String normalizeWhitespace(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    private static List<String> normalizeKeywordsForMatching(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String kw : keywords) {
            if (kw == null) continue;
            String trimmed = kw.trim().toLowerCase(Locale.ROOT);
            if (trimmed.length() < MIN_KEYWORD_LEN) continue;
            unique.add(trimmed);
        }
        return new ArrayList<>(unique);
    }

    private static List<SentenceSpan> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<SentenceSpan> spans = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '\n') {
                int end = i + 1;
                addSentenceSpan(text, spans, start, end);
                start = end;
            }
        }
        addSentenceSpan(text, spans, start, text.length());
        return spans;
    }

    private static void addSentenceSpan(String text, List<SentenceSpan> spans, int start, int end) {
        if (start >= end) {
            return;
        }
        String piece = text.substring(start, end).trim();
        if (piece.isEmpty()) {
            return;
        }
        spans.add(new SentenceSpan(piece, start));
    }

    private static ScoredSentence scoreSentence(SentenceSpan span, List<String> keywords) {
        String lower = span.text.toLowerCase(Locale.ROOT);
        int hitCount = 0;
        Set<String> matched = new HashSet<>();

        for (String kw : keywords) {
            if (kw.isEmpty()) continue;
            int idx = lower.indexOf(kw);
            if (idx >= 0) {
                matched.add(kw);
                // 统计命中次数，避免过度循环，这里简单计一次
                hitCount += 1;
            }
        }

        if (matched.isEmpty()) {
            return null;
        }

        // 短句 + 关键词覆盖度越高，得分越高
        int lengthPenalty = Math.max(0, (span.text.length() - 120) / 40);
        int score = matched.size() * 6 + hitCount * 2 - lengthPenalty;
        return new ScoredSentence(span, score, matched);
    }

    private record SentenceSpan(String text, int start) {
    }

    private record ScoredSentence(SentenceSpan span, int score, Set<String> matchedKeywords) {
    }

    private static String shortTitle(KbDocument doc) {
        String type = safe(doc.getDocType());
        if (!type.isBlank()) {
            return type.length() > 24 ? type.substring(0, 24) : type;
        }
        return "doc";
    }

    private static String shortSource(KbDocument doc) {
        Long id = doc.getId();
        String ref = (id == null) ? "kb" : "kb#" + id;
        String type = safe(doc.getDocType());
        return type.isBlank() ? ref : type + " " + ref;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
