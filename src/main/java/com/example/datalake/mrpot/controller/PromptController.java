package com.example.datalake.mrpot.controller;

import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.StepEvent;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareResponse;
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

import java.util.ArrayList;
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

  @Operation(summary = "Stream processing steps as SSE",
      description = "Runs the prompt pipeline and streams processor progress to the client.")
  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<StepEvent>> stream(@RequestBody PrepareRequest request) {
    Map<String, Object> startPayload = new LinkedHashMap<>();
    startPayload.put("query", request.getQuery());
    startPayload.put("userId", request.getUserId());
    startPayload.put("sessionId", request.getSessionId());

    Map<String, Object> normalizePayload = new LinkedHashMap<>();
    normalizePayload.put("rawInput", request.getQuery());

    Flux<ServerSentEvent<StepEvent>> bootstrap = Flux.just(
        buildEvent("0", "start", "Received request", 0, startPayload),
        buildEvent("1", "normalize-input", "Normalizing input", 10, normalizePayload)
    );

    return bootstrap.concatWith(
        Mono.defer(() -> promptPipeline.run(request))
            .flatMapMany(ctx -> {
              List<ServerSentEvent<StepEvent>> events = new ArrayList<>();
              int idx = 2;
              int progress = 20;

              String normalized = ctx.getNormalized() == null || ctx.getNormalized().isBlank()
                  ? ctx.getRawInput()
                  : ctx.getNormalized();

              Map<String, Object> normalizedPayload = new LinkedHashMap<>();
              normalizedPayload.put("normalized", normalized);

              events.add(buildEvent(String.valueOf(idx++), "normalized", "Input normalized", progress,
                  normalizedPayload));

              if (ctx.getKeywords() != null && !ctx.getKeywords().isEmpty()) {
                progress = Math.min(40, progress + 10);
                Map<String, Object> keywordPayload = new LinkedHashMap<>();
                keywordPayload.put("keywords", ctx.getKeywords());
                events.add(buildEvent(String.valueOf(idx++), "extract-keywords", "Extracted keywords", progress,
                    keywordPayload));
              }

              List<StepEvent> processorEvents = new ArrayList<>();
              if (ctx.getSteps() != null) {
                for (var step : ctx.getSteps()) {
                  processorEvents.add(StepEvent.builder()
                      .step(step.getName())
                      .note(step.getNote())
                      .progress(null)
                      .status("ok")
                      .build());
                }
              }

              if (!processorEvents.isEmpty()) {
                int processorCount = processorEvents.size();
                int remainingProgress = 50;
                int perStep = Math.max(5, remainingProgress / processorCount);

                for (StepEvent event : processorEvents) {
                  progress = Math.min(90, progress + perStep);
                  events.add(buildEvent(String.valueOf(idx++), event.getStep(), event.getNote(), progress, Map.of()));
                }
              } else {
                progress = Math.max(progress, 70);
              }

              progress = Math.min(98, progress + 5);
              Map<String, Object> finalPayload = new LinkedHashMap<>();
              finalPayload.put("finalPrompt", PromptRenderUtils.ensureFinalPrompt(ctx));

              events.add(buildEvent(String.valueOf(idx++), "finalize-prompt", "Final prompt generated", progress,
                  finalPayload));

              Map<String, Object> completePayload = new LinkedHashMap<>();
              completePayload.put("answer", ctx.getLlmAnswer());
              completePayload.put("language", ctx.getLanguage() == null ? null : ctx.getLanguage().getDisplayName());
              completePayload.put("intent", ctx.getIntent() == null ? null : ctx.getIntent().name());

              events.add(buildEvent(String.valueOf(idx), "completed", "Pipeline complete", 100, completePayload));

              return Flux.fromIterable(events);
            })
            .onErrorResume(ValidationException.class, ex -> Flux.just(
                buildErrorEvent("validation-error", ex.getMessage())
            ))
            .onErrorResume(ex -> {
              log.error("Failed to stream prompt pipeline", ex);
              return Flux.just(buildErrorEvent("unexpected-error", "Unexpected error: " + ex.getMessage()));
            })
    );
  }

  private ServerSentEvent<StepEvent> buildEvent(String id, String step, String note, Integer progress,
                                                Map<String, Object> payload) {
    StepEvent event = StepEvent.builder()
        .step(step)
        .note(note)
        .progress(progress)
        .status("ok")
        .payload(payload)
        .build();

    return ServerSentEvent.<StepEvent>builder()
        .id(id)
        .event("step-event")
        .data(event)
        .build();
  }

  private ServerSentEvent<StepEvent> buildErrorEvent(String step, String message) {
    StepEvent event = StepEvent.builder()
        .step(step)
        .note(message)
        .status("error")
        .progress(null)
        .payload(Map.of())
        .build();

    return ServerSentEvent.<StepEvent>builder()
        .id(step)
        .event("step-event")
        .data(event)
        .build();
  }
}
