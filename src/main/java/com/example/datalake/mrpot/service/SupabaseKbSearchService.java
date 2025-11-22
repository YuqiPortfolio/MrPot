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
    private static final int MAX_SNIPPET_PER_DOC = 150;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public List<KbSnippet> searchSnippets(String query,
                                          List<String> keywords,
                                          int maxSnippets,
                                          int maxTotalChars) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        if (keywords == null) {
            keywords = Collections.emptyList();
        }

        log.debug("SupabaseKbSearchService.searchSnippets query='{}', keywords={}, maxSnippets={}, maxTotalChars={}",
                query, keywords, maxSnippets, maxTotalChars);

        int docLimit = Math.min(
                MAX_DOC_CANDIDATES,
                Math.max(maxSnippets * 2, maxSnippets)
        );

        // 1) 用 query + keywords 查候选文档
        List<KbDocument> docs = searchCandidates(query, keywords, docLimit);

        // 2) 如果还空，做 fallback：拿最近几篇文档兜底
        if (docs.isEmpty()) {
            log.debug("No kb_documents matched query/keywords, fallback by latest docs");
            docs = fetchLatestDocuments(docLimit);
        }

        if (docs.isEmpty()) {
            return Collections.emptyList();
        }

        // 3) 在「总预算」内从这些文档中抽片段
        docs = docs.stream()
                .sorted(Comparator.comparingInt((KbDocument d) -> scoreDocument(d, query, keywords)).reversed()
                        .thenComparing(KbDocument::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

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

            String snippetText = extractSnippetFromContent(content, keywords, perDocBudget);
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

    private int scoreDocument(KbDocument doc, String query, List<String> keywords) {
        String content = safe(doc.getContent()).toLowerCase(Locale.ROOT);
        if (content.length() > 4000) {
            content = content.substring(0, 4000);
        }

        int score = 0;
        String trimmedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (!trimmedQuery.isEmpty()) {
            score += countOccurrences(content, trimmedQuery) * 2;
            for (String token : trimmedQuery.split("\\s+")) {
                if (token.length() > 3) {
                    score += countOccurrences(content, token);
                }
            }
        }

        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String k = kw.toLowerCase(Locale.ROOT).trim();
            if (k.isEmpty()) continue;
            score += countOccurrences(content, k) * 5;
        }

        return score;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) return 0;
        int idx = 0;
        int count = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
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

        int kwIndex = 0;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String key = "kw" + kwIndex++;
            sql.append(" OR content ILIKE :").append(key).append(" ");
            params.put(key, "%" + kw.trim() + "%");
        }
        sql.append(") ");

        sql.append(" ORDER BY id DESC LIMIT :limit");
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
     * - 如果没有命中任何关键词（或 keywords 为空），直接返回 ""；
     * - 命中时围绕第一个匹配位置截一段，并按句子边界做柔和截断。
     */
    private String extractSnippetFromContent(String content,
                                             List<String> keywords,
                                             int maxChars) {
        if (content == null) return "";
        String text = content.strip();
        if (text.isEmpty()) return "";
        if (maxChars <= 0) return "";

        if (keywords == null || keywords.isEmpty()) {
            // 要求：如果没有 keywords，直接不返回 snippet
            return "";
        }

        String lower = text.toLowerCase(Locale.ROOT);
        List<Integer> positions = keywordPositions(lower, keywords);
        if (positions.isEmpty()) {
            return "";
        }

        int bestStart = 0;
        int bestEnd = 0;
        int bestScore = -1;

        for (int pos : positions) {
            int half = maxChars / 2;
            int start = Math.max(0, pos - half);
            int end = Math.min(text.length(), start + maxChars);
            if (end - start < maxChars && end == text.length()) {
                start = Math.max(0, end - maxChars);
            }

            String windowLower = lower.substring(start, end);
            int windowScore = keywordHitCount(windowLower, keywords);
            if (windowScore > bestScore || (windowScore == bestScore && start < bestStart)) {
                bestScore = windowScore;
                bestStart = start;
                bestEnd = end;
            }
        }

        String rawSnippet = text.substring(bestStart, bestEnd);
        if (bestStart > 0) rawSnippet = "..." + rawSnippet;
        if (bestEnd < text.length()) rawSnippet = rawSnippet + "...";

        String clipped = clipToSentenceBoundary(rawSnippet, maxChars);
        return clipped;
    }

    private static List<Integer> keywordPositions(String lower, List<String> keywords) {
        List<Integer> positions = new ArrayList<>();
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String k = kw.toLowerCase(Locale.ROOT).trim();
            if (k.isEmpty()) continue;
            int idx = 0;
            while ((idx = lower.indexOf(k, idx)) >= 0) {
                positions.add(idx);
                idx += k.length();
            }
        }
        return positions;
    }

    private static int keywordHitCount(String text, List<String> keywords) {
        int score = 0;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String k = kw.toLowerCase(Locale.ROOT).trim();
            if (k.isEmpty()) continue;
            score += countOccurrences(text, k);
        }
        return score;
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
