package com.example.datalake.mrpot.prompt.controller;

import com.example.datalake.mrpot.prompt.api.PrepareRequest;
import com.example.datalake.mrpot.prompt.api.PrepareResponse;
import com.example.datalake.mrpot.prompt.domain.StepEvent;
import com.example.datalake.mrpot.prompt.service.PromptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/prompt")
@Tag(name = "Demo Streaming API", description = "Response answer and SSE ")
public class PromptController {

    private final PromptService promptService;

    public PromptController(PromptService promptService) {
        this.promptService = promptService;
    }

    @PostMapping("/prepare")
    @Operation(summary = "Prepare a session (dummy)",
            description = "Accepts a PrepareRequest and returns a PrepareResponse.")
    public Mono<ResponseEntity<PrepareResponse>> prepare(@Valid @RequestBody PrepareRequest request) {
        return Mono.fromSupplier(() -> ResponseEntity.ok(promptService.prepareSession(request)));
    }

    @Operation(summary = "Stream step events (dummy SSE)",
            description = "Streams dummy StepEvent items, 1 per configured interval, as text/event-stream.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StepEvent>> stream(@RequestParam("q") String query,
                                                   @RequestParam(value = "userId", required = false) String userId,
                                                   @RequestParam(value = "sessionId", required = false) String sessionId) {
        return promptService.streamStepEvents(query, userId, sessionId)
                .map(event -> ServerSentEvent.<StepEvent>builder()
                        .id(String.valueOf(event.ordinal()))
                        .event("step-event")
                        .data(event)
                        .build());
    }
}
