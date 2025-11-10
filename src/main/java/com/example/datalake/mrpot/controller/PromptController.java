package com.example.datalake.mrpot.controller;

import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.StepEvent;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareResponse;
import com.example.datalake.mrpot.service.PromptPipeline;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
                .map(ctx -> ResponseEntity.ok(toResponse(ctx)));
    }

    private PrepareResponse toResponse(ProcessingContext ctx) {
        String sysPrompt = """
                You are MrPot, a helpful data-lake assistant. Keep answers concise.
                """.trim();

        String normalized = ctx.getNormalized() == null || ctx.getNormalized().isBlank()
                ? ctx.getRawInput()
                : ctx.getNormalized();
        String userLabel = ctx.getUserId() == null ? "anonymous" : ctx.getUserId();
        String userPrompt = "User(" + userLabel + "): " + (normalized == null ? "" : normalized);
        String finalPrompt = sysPrompt + "\n---\n" + userPrompt;

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
                .build();
    }

    @Operation(summary = "Stream step events (dummy SSE)",
            description = "Streams 5 dummy StepEvent items, 1 per second, as text/event-stream.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StepEvent>> stream(@RequestParam("q") String query,
                                                   @RequestParam(value = "userId", required = false) String userId,
                                                   @RequestParam(value = "sessionId", required = false) String sessionId) {
        List<String> steps = List.of(
                "parse-query",
                "detect-intent",
                "extract-entities",
                "plan-execution",
                "finalize"
        );

        return Flux.interval(Duration.ofSeconds(1))
                .take(steps.size())
                .map(i -> {
                    StepEvent event = StepEvent.builder()
                            .step(steps.get(i.intValue()))
                            .note("Processed step " + (i + 1) + " for query '" + query + "'")
                            .build();

                    return ServerSentEvent.<StepEvent>builder()
                            .id(String.valueOf(i))
                            .event("step-event")
                            .data(event)
                            .build();
                });
    }
}
