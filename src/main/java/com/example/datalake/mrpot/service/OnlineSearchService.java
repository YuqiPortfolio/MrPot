package com.example.datalake.mrpot.service;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.StringJoiner;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineSearchService {

    private static final int MAX_REFERENCES = 3;
    private static final int MAX_REFERENCE_LENGTH = 240;

    private final ChatModel chatModel;

    public Mono<String> searchConciseReferences(String query, List<String> keywords) {
        if ((keywords == null || keywords.isEmpty()) && (query == null || query.isBlank())) {
            return Mono.just("");
        }

        String prompt = buildPrompt(query, keywords);
        return Mono.fromCallable(() -> chatModel.chat(prompt))
                .map(this::clipReferenceBlock)
                .doOnError(ex -> log.warn("Online reference search failed", ex))
                .onErrorReturn("");
    }

    private String buildPrompt(String query, List<String> keywords) {
        StringJoiner kwJoiner = new StringJoiner(", ");
        if (keywords != null) {
            keywords.stream()
                    .filter(kw -> kw != null && !kw.isBlank())
                    .forEach(kw -> kwJoiner.add(kw.trim()));
        }

        return """
                You are an online research assistant.
                Based on the query and keywords, list up to %d concise references from credible sources.
                Each bullet must stay under %d characters, avoid filler, and keep the phrasing brief.
                Query: %s
                Keywords: %s
                Format:
                - title — brief source or context
                - title — brief source or context
                """.formatted(
                MAX_REFERENCES,
                MAX_REFERENCE_LENGTH,
                safe(query),
                kwJoiner.length() == 0 ? "(none)" : kwJoiner.toString()
        );
    }

    private String clipReferenceBlock(String block) {
        if (block == null) {
            return "";
        }
        String trimmed = block.strip();
        int maxLength = MAX_REFERENCE_LENGTH * MAX_REFERENCES;
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength).strip() + "…";
    }

    private static String safe(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().isBlank() ? "" : text.trim();
    }
}
