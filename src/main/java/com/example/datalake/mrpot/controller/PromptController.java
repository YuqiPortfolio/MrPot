package com.example.datalake.mrpot.controller;

import com.example.datalake.mrpot.model.StepEvent;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareResponse;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;


@RestController
@RequestMapping("/v1/prompt")
@Tag(name = "Demo Streaming API", description = "Response answer and SSE ")
public class PromptController {
    @PostMapping("/prepare")
    @Operation(summary = "Prepare a session (dummy)",
            description = "Accepts a PrepareRequest and returns a PrepareResponse.")
    public Mono<ResponseEntity<PrepareResponse>> prepare(@RequestBody PrepareRequest req) {
        String sysPrompt = """
                You are MrPot, a helpful data-lake assistant. Keep answers concise.
                """.trim();

        String userPrompt = "User(" + (req.getUserId() == null ? "anonymous" : req.getUserId()) + "): " + req.getQuery();
        String finalPrompt = sysPrompt + "\n---\n" + userPrompt;

        PrepareResponse response = PrepareResponse.builder()
                .systemPrompt(sysPrompt)
                .userPrompt(userPrompt)
                .finalPrompt(finalPrompt)
                .language("en")
                .intent("demo.prepare")
                .tags(List.of("demo", "hardcoded", "sse", "prepare"))
                .entities(Map.of(
                        "userId", req.getUserId(),
                        "sessionId", req.getSessionId() == null ? UUID.randomUUID().toString() : req.getSessionId(),
                        "query", req.getQuery()
                ))
                .steps(List.of()) // no real StepLog entries for now
                .build();

        return Mono.just(ResponseEntity.ok(response));
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
