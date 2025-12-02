package com.example.datalake.mrpot.controller;

import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.StepEvent;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareResponse;
import com.example.datalake.mrpot.service.PromptPipeline;
import com.example.datalake.mrpot.sse.ThinkingStep;
import com.example.datalake.mrpot.sse.ThinkingStepsMapper;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import com.example.datalake.mrpot.validation.ValidationException;
import dev.langchain4j.exception.RateLimitException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/v1/prompt")
@Tag(name = "Demo Streaming API", description = "Response answer and SSE ")
@RequiredArgsConstructor
public class PromptController {

    private final PromptPipeline promptPipeline;
    private final ThinkingStepsMapper thinkingStepsMapper;

    // ==================== 非流式：一次性 prepare ====================

    @PostMapping("/prepare")
    @Operation(
            summary = "Prepare a session using the processing pipeline",
            description = "Runs the configured text processors on the payload and returns the resulting context."
    )
    public Mono<ResponseEntity<PrepareResponse>> prepare(@RequestBody PrepareRequest req) {
        return promptPipeline.run(req)
                .map(ctx -> ResponseEntity.ok(toResponse(ctx)))
                .onErrorResume(RateLimitException.class, ex ->
                        Mono.just(ResponseEntity.status(429).body(toRateLimitResponse(ex))))
                .onErrorResume(ValidationException.class, ex ->
                        Mono.just(ResponseEntity.badRequest().body(toErrorResponse(ex))))
                .onErrorResume(ex -> {
                    log.error("Unexpected failure while preparing prompt", ex);
                    return Mono.just(ResponseEntity.internalServerError().body(toUnexpectedErrorResponse(ex)));
                });
    }

    // 把 ProcessingContext 映射成 PrepareResponse（非流式和流式最终结果共用）
    private PrepareResponse toResponse(ProcessingContext ctx) {
        String sysPrompt = PromptRenderUtils.ensureSystemPrompt(ctx);
        String userPrompt = PromptRenderUtils.ensureUserPrompt(ctx);
        String finalPrompt = PromptRenderUtils.ensureFinalPrompt(ctx);

        String normalized = ctx.getNormalized() == null || ctx.getNormalized().isBlank()
                ? ctx.getRawInput()
                : ctx.getNormalized();

        Language language = ctx.getLanguage();
        String langDisplay = language == null
                ? null
                : (language.getDisplayName() != null ? language.getDisplayName() : language.getIsoCode());

        String sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
            ctx.setSessionId(sessionId);
        }

        Map<String, Object> entities = new LinkedHashMap<>();
        if (ctx.getEntities() != null) {
            entities.putAll(ctx.getEntities());
        }
        entities.put("userId", ctx.getUserId());
        entities.put("sessionId", sessionId);
        entities.put("query", ctx.getRawInput());
        entities.put("normalized", normalized);

        return PrepareResponse.builder()
                .systemPrompt(sysPrompt)
                .userPrompt(userPrompt)
                .finalPrompt(finalPrompt)
                .language(langDisplay)
                .intent(ctx.getIntent() == null ? null : ctx.getIntent().name())
                .tags(ctx.getTags() == null ? List.of() : ctx.getTags().stream().toList())
                .entities(entities)
                .steps(ctx.getSteps() == null ? List.of() : List.copyOf(ctx.getSteps()))
                .notices(ctx.getValidationNotices() == null ? List.of() : List.copyOf(ctx.getValidationNotices()))
                .errors(List.of())
                .answer(ctx.getLlmAnswer())
                .build();
    }

    private PrepareResponse toErrorResponse(ValidationException ex) {
        return PrepareResponse.builder()
                .notices(List.of())
                .errors(List.copyOf(ex.getReasons()))
                .build();
    }

    private PrepareResponse toUnexpectedErrorResponse(Throwable ex) {
        String detail = ex.getMessage();
        String message = (detail == null || detail.isBlank())
                ? "Unexpected error occurred."
                : "Unexpected error: " + detail;
        return PrepareResponse.builder()
                .notices(List.of())
                .errors(List.of(message))
                .build();
    }

    private PrepareResponse toRateLimitResponse(RateLimitException ex) {
        String detail = ex == null ? null : ex.getMessage();
        String message = (detail == null || detail.isBlank())
                ? "OpenAI rate limit or quota was exceeded. Please try again later."
                : "OpenAI rate limit or quota was exceeded: " + detail;

        return PrepareResponse.builder()
                .notices(List.of())
                .errors(List.of(message))
                .build();
    }

    // ==================== 流式：按步骤输出 SSE ====================

    @Operation(
            summary = "Stream step events",
            description = "Runs the full processing pipeline (same as /prepare) and streams step events plus the final response."
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> stream(@RequestParam("q") String query,
                                           @RequestParam(value = "userId", required = false) String userId,
                                           @RequestParam(value = "sessionId", required = false) String sessionId) {

        PrepareRequest req = PrepareRequest.builder()
                .query(query)
                .userId(userId)
                .sessionId(sessionId)
                .build();

        return promptPipeline.runStreaming(req)
                .transform(source -> {
                    // replay: 复用同一个上游流，既用于 step-event，又用于最后一次 ctx → prepare-response
                    var replayed = source.replay();

                    // 记录“已经发送到第几个 step”
                    AtomicInteger lastSentSize = new AtomicInteger(0);

                    // 1) 对每个 ProcessingContext，只发送“新增的步骤”
                    Flux<ServerSentEvent<?>> stepEvents = replayed
                            .flatMap(ctx -> {
                                List<ThinkingStep> steps = thinkingStepsMapper.toThinkingSteps(ctx);
                                if (steps == null || steps.isEmpty()) {
                                    return Flux.empty();
                                }

                                int from = lastSentSize.get();
                                int total = steps.size();
                                if (from >= total) {
                                    // 没有新增步骤
                                    return Flux.empty();
                                }

                                // 更新“已发送计数”
                                lastSentSize.set(total);

                                // 只发 [from, total) 这一段增量
                                return Flux.fromIterable(steps.subList(from, total))
                                        .map(this::toStepEvent);
                            });

                    // 2) 整条流结束后，拿最后一个 ctx → prepare-response
                    Mono<ServerSentEvent<?>> responseEvent = replayed
                            .last()
                            .map(ctx -> ServerSentEvent.builder(toResponse(ctx))
                                    .event("prepare-response")
                                    .build());

                    // 启动 replay
                    replayed.connect();

                    // 3) step-event 串起来 + 最后的 prepare-response + done
                    return stepEvents
                            .concatWith(responseEvent)
                            .concatWith(Mono.just(
                                    ServerSentEvent.builder("done")
                                            .event("done")
                                            .build()
                            ));
                })
                .onErrorResume(RateLimitException.class, ex -> Flux.just(
                        ServerSentEvent.builder(toRateLimitResponse(ex))
                                .event("error")
                                .build(),
                        ServerSentEvent.builder("done")
                                .event("done")
                                .build()
                ))
                .onErrorResume(ValidationException.class, ex -> Flux.just(
                        ServerSentEvent.builder(toErrorResponse(ex))
                                .event("error")
                                .build(),
                        ServerSentEvent.builder("done")
                                .event("done")
                                .build()
                ))
                .onErrorResume(ex -> {
                    log.error("Unexpected failure while streaming prompt", ex);
                    return Flux.just(
                            ServerSentEvent.builder(toUnexpectedErrorResponse(ex))
                                    .event("error")
                                    .build(),
                            ServerSentEvent.builder("done")
                                    .event("done")
                                    .build()
                    );
                });
    }

    // 把一个 ThinkingStep → StepEvent（SSE 数据）
    private ServerSentEvent<StepEvent> toStepEvent(ThinkingStep step) {
        return ServerSentEvent.builder(
                        StepEvent.builder()
                                .step(step.getProcessor())   // internal name: e.g. "unified-clean-correct"
                                .title(step.getTitle())      // human-readable title
                                .note(step.getDetail())      // detail: normalized text / keywords / 搜索参考 等
                                .build()
                )
                .id(String.valueOf(step.getIndex())) // 用步骤序号当 id，方便前端去重/渲染
                .event("step-event")
                .build();
    }
}
