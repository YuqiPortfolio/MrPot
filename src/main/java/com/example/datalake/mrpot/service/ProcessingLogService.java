package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.dao.ProcessingContextLogRepository;
import com.example.datalake.mrpot.entity.ProcessingContextEntity;
import com.example.datalake.mrpot.entity.ProcessingStepLogEntity;
import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.StepLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingLogService {

    private final ProcessingContextLogRepository repository;

    public Mono<ProcessingContext> persistContext(ProcessingContext ctx) {
        return Mono.fromCallable(() -> {
            repository.save(mapToEntity(ctx));
            return ctx;
        }).onErrorResume(ex -> {
            log.warn("Failed to persist processing context for session {}: {}", ctx.getSessionId(), ex.getMessage());
            return Mono.just(ctx);
        });
    }

    private ProcessingContextEntity mapToEntity(ProcessingContext ctx) {
        ProcessingContextEntity entity = new ProcessingContextEntity();
        entity.setUserId(ctx.getUserId());
        entity.setSessionId(ctx.getSessionId());
        entity.setRawInput(Optional.ofNullable(ctx.getRawInput()).orElse(""));
        entity.setNormalized(ctx.getNormalized());
        entity.setLanguage(resolveLanguage(ctx.getLanguage()));
        entity.setIntent(resolveIntent(ctx.getIntent()));
        entity.setCorrected(ctx.getCorrected());
        entity.setChangeRatio(Double.isNaN(ctx.getChangeRatio()) ? null : ctx.getChangeRatio());
        entity.setIndexText(ctx.getIndexText());
        entity.setIndexLanguage(ctx.getIndexLanguage());
        entity.setTemplateCode(ctx.getTemplate() == null ? null : ctx.getTemplate().getId());
        entity.setSystemPrompt(ctx.getSystemPrompt());
        entity.setUserPrompt(ctx.getUserPrompt());
        entity.setFinalPrompt(ctx.getFinalPrompt());
        entity.setKbContext(ctx.getKbContext());
        entity.setLlmQuestion(ctx.getLlmQuestion());
        entity.setKbSnippetCount(ctx.getKbSnippetCount());
        entity.setCharLimit(ctx.getCharLimit());
        entity.setCacheKey(ctx.getCacheKey());
        entity.setCacheHit(ctx.isCacheHit());
        entity.setCacheFrequency(ctx.getCacheFrequency());
        entity.setCommonResponse(ctx.isCommonResponse());
        entity.setLlmAnswer(ctx.getLlmAnswer());

        Instant now = Optional.ofNullable(ctx.getNow()).orElse(Instant.now());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        List<ProcessingStepLogEntity> stepEntities = buildStepEntities(ctx.getSteps(), entity);
        entity.setSteps(stepEntities);

        return entity;
    }

    private String resolveLanguage(Language language) {
        if (language == null) {
            return Language.und().getIsoCode();
        }
        return Optional.ofNullable(language.getIsoCode()).orElse(Language.und().getIsoCode());
    }

    private String resolveIntent(Intent intent) {
        return intent == null ? Intent.UNKNOWN.name() : intent.name();
    }

    private List<ProcessingStepLogEntity> buildStepEntities(List<StepLog> steps, ProcessingContextEntity parent) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }

        List<ProcessingStepLogEntity> entities = new ArrayList<>(steps.size());
        AtomicInteger order = new AtomicInteger(1);
        for (StepLog log : steps) {
            if (log == null || log.getName() == null) {
                continue;
            }
            ProcessingStepLogEntity entity = new ProcessingStepLogEntity();
            entity.setContext(parent);
            entity.setStepOrder(order.getAndIncrement());
            entity.setName(log.getName());
            entity.setNote(log.getNote());
            entity.setAt(Objects.requireNonNullElse(log.getAt(), Instant.now()));
            entities.add(entity);
        }
        return entities;
    }
}
