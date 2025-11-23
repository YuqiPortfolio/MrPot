package com.example.datalake.mrpot.controller;

import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareResponse;
import com.example.datalake.mrpot.service.LangChain4jRagService;
import com.example.datalake.mrpot.service.PromptPipeline;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import com.example.datalake.mrpot.validation.ValidationException;
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

@Slf4j
@RestController
@RequestMapping("/v1/prompt")
@Tag(name = "Demo Streaming API", description = "Response answer and SSE ")
@RequiredArgsConstructor
public class PromptController {

  private final PromptPipeline promptPipeline;
  private final LangChain4jRagService ragService;

  @PostMapping("/prepare")
  @Operation(summary = "Prepare a session using the processing pipeline",
      description = "Runs the configured text processors on the payload and returns the resulting context.")
  public Mono<ResponseEntity<PrepareResponse>> prepare(@RequestBody PrepareRequest req) {
    return promptPipeline.run(req)
        .map(ctx -> ResponseEntity.ok(toResponse(ctx)))
        .onErrorResume(ValidationException.class, ex ->
            Mono.just(ResponseEntity.badRequest().body(toErrorResponse(ex))))
        .onErrorResume(ex -> {
          log.error("Unexpected failure while preparing prompt", ex);
          return Mono.just(ResponseEntity.internalServerError().body(toUnexpectedErrorResponse(ex)));
        });
  }

  private PrepareResponse toResponse(ProcessingContext ctx) {
    String sysPrompt = PromptRenderUtils.ensureSystemPrompt(ctx);
    String userPrompt = PromptRenderUtils.ensureUserPrompt(ctx);
    String finalPrompt = PromptRenderUtils.ensureFinalPrompt(ctx);

    String normalized = ctx.getNormalized() == null || ctx.getNormalized().isBlank()
        ? ctx.getRawInput()
        : ctx.getNormalized();

    Language language = ctx.getLanguage();
    String langDisplay = language == null ? null :
        (language.getDisplayName() != null ? language.getDisplayName() : language.getIsoCode());

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
              .sessionId(sessionId)
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

  @Operation(summary = "Stream a chat response with contextual memory",
      description = "Runs the prompt pipeline, feeds the result into LangChain4j with chat memory, and streams the answer as SSE.")
  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<Map<String, Object>>> stream(@RequestBody PrepareRequest req) {
    return promptPipeline.run(req)
        .flatMapMany(this::toStream)
        .onErrorResume(ValidationException.class, ex -> Flux.just(
            buildSse("error", Map.of("errors", List.copyOf(ex.getReasons())))))
        .onErrorResume(ex -> {
          log.error("Unexpected failure while streaming prompt", ex);
          String detail = ex.getMessage();
          String message = (detail == null || detail.isBlank())
              ? "Unexpected error occurred."
              : detail;
          return Flux.just(buildSse("error", Map.of("errors", List.of(message))));
        });
  }

  private Flux<ServerSentEvent<Map<String, Object>>> toStream(ProcessingContext ctx) {
    String sessionId = ctx.getSessionId();
    String language = ctx.getLanguage() == null ? null : ctx.getLanguage().getIsoCode();

    Map<String, Object> start = new LinkedHashMap<>();
    start.put("sessionId", sessionId);
    start.put("language", language);
    start.put("intent", ctx.getIntent() == null ? null : ctx.getIntent().name());
    start.put("steps", ctx.getSteps() == null ? List.of() : List.copyOf(ctx.getSteps()));

    StringBuilder answerCollector = new StringBuilder();

    Flux<ServerSentEvent<Map<String, Object>>> stream = ragService.streamWithMemory(ctx)
        .doOnNext(answerCollector::append)
        .map(token -> buildSse("token", Map.of("content", token)));

    Flux<ServerSentEvent<Map<String, Object>>> completed = Flux.defer(() -> {
      String fullAnswer = answerCollector.toString();
      ctx.setLlmAnswer(fullAnswer);

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("sessionId", sessionId);
      payload.put("answer", fullAnswer);
      payload.put("docIds", ctx.getLlmDocIds() == null ? List.of() : ctx.getLlmDocIds());
      payload.put("steps", ctx.getSteps() == null ? List.of() : ctx.getSteps());

      return Flux.just(buildSse("complete", payload));
    });

    return Flux.concat(
        Flux.just(buildSse("start", start)),
        stream,
        completed
    );
  }

  private ServerSentEvent<Map<String, Object>> buildSse(String event, Map<String, Object> payload) {
    return ServerSentEvent.<Map<String, Object>>builder()
        .event(event)
        .data(payload)
        .build();
  }
}
