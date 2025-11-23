package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.dao.PromptSessionRepository;
import com.example.datalake.mrpot.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PromptSessionLoggingService {

    private final PromptSessionRepository promptSessionRepository;

    @Transactional
    public PromptSession recordSession(ProcessingContext ctx) {
        PromptSession session = new PromptSession()
                .setUserId(ctx.getUserId())
                .setSessionId(ctx.getSessionId())
                .setRawInput(ctx.getRawInput())
                .setNormalized(ctx.getNormalized())
                .setLanguage(Optional.ofNullable(ctx.getLanguage()).map(Language::getIsoCode).orElse(null))
                .setIntent(ctx.getIntent() == null ? null : ctx.getIntent().name())
                .setSystemPrompt(ctx.getSystemPrompt())
                .setUserPrompt(ctx.getUserPrompt())
                .setFinalPrompt(ctx.getFinalPrompt())
                .setAnswer(ctx.getLlmAnswer())
                .setCacheHit(ctx.isCacheHit())
                .setCacheKey(ctx.getCacheKey())
                .setCreatedAt(Optional.ofNullable(ctx.getNow()).orElseGet(Instant::now));

        if (ctx.getTags() != null) {
            session.getTags().addAll(ctx.getTags());
        }
        if (ctx.getValidationNotices() != null) {
            session.getValidationNotices().addAll(ctx.getValidationNotices());
        }
        if (ctx.getSteps() != null) {
            for (StepLog stepLog : ctx.getSteps()) {
                PromptSessionStep step = new PromptSessionStep()
                        .setSession(session)
                        .setName(stepLog.getName())
                        .setNote(stepLog.getNote())
                        .setAt(stepLog.getAt());
                session.getSteps().add(step);
            }
        }
        if (ctx.getLlmDocIds() != null) {
            for (Long docId : ctx.getLlmDocIds()) {
                if (docId == null) continue;
                PromptSessionDocument doc = new PromptSessionDocument()
                        .setSession(session)
                        .setDocumentId(docId);
                session.getDocuments().add(doc);
            }
        }

        return promptSessionRepository.save(session);
    }
}
