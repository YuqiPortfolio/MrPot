package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.dao.KbDocumentRepository;
import com.example.datalake.mrpot.dao.KeywordsLexiconRepository;
import com.example.datalake.mrpot.model.KbDocument;
import com.example.datalake.mrpot.model.KeywordsLexicon;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReferencePersistenceService {

    private static final String STEP_NAME = "reference-persistence";
    private static final String DEFAULT_DOC_TYPE = "reference";

    private final KbDocumentRepository kbDocumentRepository;
    private final KeywordsLexiconRepository keywordsLexiconRepository;
    private final ObjectMapper objectMapper;

    public Mono<ProcessingContext> persistAnswerAndKeywords(ProcessingContext ctx) {
        return Mono.fromCallable(() -> {
            if (ctx == null) {
                return ctx;
            }

            String answer = safe(ctx.getLlmAnswer());
            if (answer.isBlank()) {
                return ctx.addStep(STEP_NAME, "skip-empty-answer");
            }

            List<String> normalizedKeywords = normalizeKeywords(ctx.getKeywords());
            if (!normalizedKeywords.isEmpty()) {
                persistKeywords(normalizedKeywords);
            }

            KbDocument saved = persistDocument(ctx, answer, normalizedKeywords);
            if (saved != null && saved.getId() != null) {
                if (ctx.getLlmDocIds() == null) {
                    ctx.setLlmDocIds(new java.util.ArrayList<>());
                }
                ctx.getLlmDocIds().add(saved.getId());
            }

            String note = "doc=" + (saved != null ? saved.getId() : "-")
                    + ", keywords=" + normalizedKeywords.size();
            return ctx.addStep(STEP_NAME, note);
        });
    }

    private KbDocument persistDocument(ProcessingContext ctx, String answer, List<String> keywords) {
        KbDocument doc = new KbDocument();
        doc.setDocType(DEFAULT_DOC_TYPE);
        doc.setContent(answer);
        doc.setMetadata(buildMetadata(ctx, keywords));
        try {
            return kbDocumentRepository.save(doc);
        } catch (Exception e) {
            log.warn("Failed to persist reference document: {}", e.getMessage());
            return null;
        }
    }

    private String buildMetadata(ProcessingContext ctx, List<String> keywords) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", ctx.getUserId());
        metadata.put("sessionId", ctx.getSessionId());
        metadata.put("query", ctx.getRawInput());
        metadata.put("normalized", ctx.getNormalized());
        metadata.put("keywords", keywords);
        metadata.put("createdAt", Instant.now().toString());

        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write metadata", e);
        }
    }

    private void persistKeywords(List<String> keywords) {
        for (String canonical : keywords) {
            KeywordsLexicon existing = keywordsLexiconRepository.findById(canonical).orElse(null);
            if (existing != null) {
                if (!existing.isActive()) {
                    existing.setActive(true);
                    keywordsLexiconRepository.save(existing);
                }
                continue;
            }

            KeywordsLexicon entity = new KeywordsLexicon();
            entity.setCanonical(canonical);
            entity.setSynonyms(List.of());
            entity.setActive(true);
            keywordsLexiconRepository.save(entity);
        }
    }

    private List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }

        Set<String> unique = new LinkedHashSet<>();
        for (String kw : keywords) {
            if (kw == null) continue;
            String trimmed = kw.trim();
            if (trimmed.isEmpty()) continue;
            unique.add(trimmed.toLowerCase(Locale.ROOT));
        }

        return unique.stream().toList();
    }

    private static String safe(String s) {
        return Objects.requireNonNullElse(s, "");
    }
}
