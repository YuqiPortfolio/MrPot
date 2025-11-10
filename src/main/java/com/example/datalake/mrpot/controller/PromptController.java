package com.example.datalake.mrpot.controller;

import com.example.datalake.mrpot.model.StepEvent;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareResponse;
import com.example.datalake.mrpot.service.PromptPipeline;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class PromptController {
  private final PromptPipeline promptPipeline;

  @PostMapping("/prepare")
  @Operation(
      summary = "Prepare a session (prompt pipeline)",
      description = "Runs the text pipeline to normalize the query and returns prompt details.")
  public Mono<ResponseEntity<PrepareResponse>> prepare(@Valid @RequestBody PrepareRequest req) {
    return promptPipeline.prepare(req).map(ResponseEntity::ok);
  }

  @Operation(
      summary = "Stream step events (dummy SSE)",
      description = "Streams 5 dummy StepEvent items, 1 per second, as text/event-stream.")
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<StepEvent>> stream(
      @RequestParam("q") String query,
      @RequestParam(value = "userId", required = false) String userId,
      @RequestParam(value = "sessionId", required = false) String sessionId) {
    List<String> steps =
        List.of("parse-query", "detect-intent", "extract-entities", "plan-execution", "finalize");

    return Flux.interval(Duration.ofSeconds(1))
        .take(steps.size())
        .map(
            i -> {
              StepEvent event =
                  StepEvent.builder()
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
