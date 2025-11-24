package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbSnippet;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 基于 LangChain4j 嵌入存储的 KB 检索实现：
 * - 将 query + keywords 拼成检索文本，交给 EmbeddingStore 做向量搜索
 * - 仅依赖 RAG/Embedding，不再直接跑 SQL ILIKE
 * - 对返回片段做全局字符预算控制，保证答案简洁
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupabaseKbSearchService implements KbSearchService {

    private static final int MAX_QUERY_CHARS = 320;
    private static final int MAX_MATCHES = 10;
    private static final int MAX_SNIPPET_CHARS = 220;
    private static final double MIN_SCORE = 0.45;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Override
    public List<KbSnippet> searchSnippets(String query,
                                          List<String> keywords,
                                          int maxSnippets,
                                          int maxTotalChars) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        List<String> safeKeywords = Optional.ofNullable(keywords)
                .orElseGet(Collections::emptyList)
                .stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        String searchText = buildSearchText(query, safeKeywords);

        log.debug("SupabaseKbSearchService.searchSnippets query='{}', keywords={}, maxSnippets={}, maxTotalChars={}, searchText='{}'",
                query, safeKeywords, maxSnippets, maxTotalChars, searchText);

        Embedding embedding = embeddingModel.embed(searchText).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding)
                .maxResults(Math.min(MAX_MATCHES, Math.max(maxSnippets * 2, maxSnippets)))
                .minScore(MIN_SCORE)
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        if (result == null || result.matches().isEmpty()) {
            return Collections.emptyList();
        }

        int remaining = maxTotalChars;
        List<KbSnippet> snippets = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            if (match == null) continue;
            if (snippets.size() >= maxSnippets) break;
            if (remaining <= 0) break;

            TextSegment segment = match.embedded();
            if (segment == null || segment.text() == null) continue;

            String snippetText = clipToBudget(segment.text(), Math.min(remaining, MAX_SNIPPET_CHARS));
            snippetText = normalizeWhitespace(snippetText);
            if (snippetText.isBlank()) continue;

            Metadata metadata = segment.metadata() == null ? new Metadata() : segment.metadata();

            KbSnippet snippet = KbSnippet.builder()
                    .docId(extractDocId(metadata))
                    .title(extractTitle(metadata))
                    .source(extractSource(metadata))
                    .snippet(snippetText)
                    .score(match.score() == null ? 0.0 : match.score())
                    .build();

            snippets.add(snippet);
            remaining -= snippetText.length();
        }

        return snippets;
    }

    private static String buildSearchText(String query, List<String> keywords) {
        String trimmedQuery = Optional.ofNullable(query).map(String::trim).orElse("");
        String joinedKeywords = (keywords == null || keywords.isEmpty())
                ? ""
                : " | keywords: " + String.join(", ", keywords);

        String combined = (trimmedQuery + joinedKeywords).strip();
        if (combined.length() > MAX_QUERY_CHARS) {
            return combined.substring(0, MAX_QUERY_CHARS);
        }
        return combined;
    }

    private static String clipToBudget(String text, int budget) {
        if (text == null) return "";
        String normalized = text.strip();
        if (normalized.length() <= budget) {
            return normalized;
        }

        int cut = lastSentenceBoundary(normalized, budget);
        if (cut < 40) {
            cut = budget;
        }
        return normalized.substring(0, Math.min(normalized.length(), cut)).strip() + "...";
    }

    private static int lastSentenceBoundary(String text, int upperBound) {
        if (text == null || text.isEmpty()) return -1;
        int maxIdx = Math.min(text.length(), upperBound);
        String slice = text.substring(0, maxIdx);
        int last = -1;
        char[] marks = {'.', '?', '!', '。', '？', '！', '\n'};
        for (char m : marks) {
            int idx = slice.lastIndexOf(m);
            if (idx > last) last = idx;
        }
        return (last == -1) ? -1 : last + 1;
    }

    private static Long extractDocId(Metadata metadata) {
        String id = Optional.ofNullable(metadata.getString("doc_id"))
                .orElseGet(() -> metadata.getString("id"));

        if (id == null || id.isBlank()) {
            return null;
        }

        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractTitle(Metadata metadata) {
        String title = metadata.getString("title");
        if (title == null || title.isBlank()) {
            title = metadata.getString("doc_type");
        }
        if (title == null) {
            return "doc";
        }
        return title.length() > 50 ? title.substring(0, 50) : title;
    }

    private static String extractSource(Metadata metadata) {
        String source = metadata.getString("source");
        if (source != null && !source.isBlank()) {
            return source;
        }
        String docType = metadata.getString("doc_type");
        Long docId = extractDocId(metadata);
        if (docType == null && docId == null) {
            return "kb";
        }
        StringBuilder sb = new StringBuilder();
        if (docType != null && !docType.isBlank()) {
            sb.append(docType.trim());
        }
        if (docId != null) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("kb#").append(docId);
        }
        return sb.isEmpty() ? "kb" : sb.toString();
    }

    private static String normalizeWhitespace(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u00A0]+", " ").trim();
    }
}
