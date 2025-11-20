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
    private static final int MAX_SNIPPET_PER_DOC = 600;

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
            if (snippetText.isBlank()) continue;

            KbSnippet snippet = KbSnippet.builder()
                    .docId(doc.getId())
                    // 暂时没有 title/source 字段，你以后可以从 metadataJson 里解析
                    .title(null)
                    .source("kb_documents")
                    .snippet(snippetText)
                    .score(0.0)
                    .build();

            result.add(snippet);
            remaining -= snippetText.length();
        }

        return result;
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

        // 用 query 做一个模糊匹配
        sql.append(" AND content ILIKE :q ");
        params.put("q", "%" + trimmedQuery + "%");

        // keywords OR 匹配
        int kwIndex = 0;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            String key = "kw" + kwIndex++;
            sql.append(" OR content ILIKE :").append(key).append(" ");
            params.put(key, "%" + kw.trim() + "%");
        }

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
     * - 优先围绕第一个关键词命中的位置；
     * - 如果没有命中任何关键词，则取开头的一段。
     */
    private String extractSnippetFromContent(String content,
                                             List<String> keywords,
                                             int maxChars) {
        if (content == null) return "";
        if (content.length() <= maxChars) {
            return content;
        }

        String lower = content.toLowerCase(Locale.ROOT);
        int bestIdx = -1;

        if (keywords != null && !keywords.isEmpty()) {
            for (String kw : keywords) {
                if (kw == null || kw.isBlank()) continue;
                String kwLower = kw.toLowerCase(Locale.ROOT).trim();
                if (kwLower.isEmpty()) continue;

                int idx = lower.indexOf(kwLower);
                if (idx >= 0 && (bestIdx == -1 || idx < bestIdx)) {
                    bestIdx = idx;
                }
            }
        }

        // 没有任何关键词命中：直接用开头一段
        if (bestIdx == -1) {
            return content.substring(0, maxChars) + "...";
        }

        int half = maxChars / 2;
        int start = Math.max(0, bestIdx - half);
        int end = Math.min(content.length(), start + maxChars);

        if (end - start < maxChars && end == content.length()) {
            start = Math.max(0, end - maxChars);
        }

        String snippet = content.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < content.length()) snippet = snippet + "...";

        return snippet;
    }

    private static String normalizeWhitespace(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
