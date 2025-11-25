package com.example.datalake.mrpot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.model.StepEvent;
import com.example.datalake.mrpot.model.StepLog;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareResponse;
import com.example.datalake.mrpot.service.PromptPipeline;
import com.example.datalake.mrpot.sse.ThinkingStepsMapper;
import com.example.datalake.mrpot.validation.ValidationException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class PromptControllerTest {

  @Test
  void prepareReturnsValidationErrors() {
    PromptPipeline pipeline = mock(PromptPipeline.class);
    ThinkingStepsMapper thinkingStepsMapper = new ThinkingStepsMapper();
    PrepareRequest request = new PrepareRequest().setQuery("   ");

    ValidationException exception = new ValidationException("User input must not be blank.");
    when(pipeline.run(request)).thenReturn(Mono.error(exception));

    PromptController controller = new PromptController(pipeline, thinkingStepsMapper);

    ResponseEntity<PrepareResponse> response = controller.prepare(request).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCodeValue()).isEqualTo(400);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getErrors())
        .containsExactly("User input must not be blank.");
    assertThat(response.getBody().getNotices()).isEmpty();
  }

  @Test
  void prepareReturnsUnexpectedErrors() {
    PromptPipeline pipeline = mock(PromptPipeline.class);
    ThinkingStepsMapper thinkingStepsMapper = new ThinkingStepsMapper();
    PrepareRequest request = new PrepareRequest().setQuery("question");

    when(pipeline.run(request)).thenReturn(Mono.error(new IllegalStateException("boom")));

    PromptController controller = new PromptController(pipeline, thinkingStepsMapper);

    ResponseEntity<PrepareResponse> response = controller.prepare(request).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCodeValue()).isEqualTo(500);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getErrors())
        .containsExactly("Unexpected error: boom");
    assertThat(response.getBody().getNotices()).isEmpty();
  }

  @Test
  void prepareReturnsRateLimitErrors() {
    PromptPipeline pipeline = mock(PromptPipeline.class);
    ThinkingStepsMapper thinkingStepsMapper = new ThinkingStepsMapper();
    PrepareRequest request = new PrepareRequest().setQuery("question");

    RateLimitException exception = new RateLimitException("quota exceeded");
    when(pipeline.run(request)).thenReturn(Mono.error(exception));

    PromptController controller = new PromptController(pipeline, thinkingStepsMapper);

    ResponseEntity<PrepareResponse> response = controller.prepare(request).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCodeValue()).isEqualTo(429);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getErrors())
        .containsExactly("OpenAI rate limit or quota was exceeded: quota exceeded");
    assertThat(response.getBody().getNotices()).isEmpty();
  }

  @Test
  void streamEmitsStepEventsAndFinalResponse() {
    PromptPipeline pipeline = mock(PromptPipeline.class);
    ThinkingStepsMapper thinkingStepsMapper = new ThinkingStepsMapper();
    PromptController controller = new PromptController(pipeline, thinkingStepsMapper);

    PrepareRequest expectedRequest = PrepareRequest.builder()
        .query("hello")
        .userId("u1")
        .sessionId("s1")
        .build();

    ProcessingContext first = new ProcessingContext()
        .setRawInput("hello")
        .setSessionId("s1")
        .setSystemPrompt("sys")
        .setSteps(new ArrayList<>(List.of(
            new StepLog("unified-clean-correct", "normalized", Instant.EPOCH)
        )));

    ProcessingContext second = new ProcessingContext()
        .setRawInput("hello")
        .setSessionId("s1")
        .setSystemPrompt("sys")
        .setSteps(new ArrayList<>(List.of(
            new StepLog("unified-clean-correct", "normalized", Instant.EPOCH),
            new StepLog("intent-classifier", "intent detected", Instant.EPOCH.plusSeconds(1))
        )));

    when(pipeline.runStreaming(expectedRequest)).thenReturn(Flux.just(first, second));

    List<ServerSentEvent<?>> events = controller.stream("hello", "u1", "s1").collectList().block();

    assertThat(events).isNotNull();
    assertThat(events).hasSize(4);

    ServerSentEvent<?> stepOne = events.get(0);
    ServerSentEvent<?> stepTwo = events.get(1);
    ServerSentEvent<?> finalResponse = events.get(2);
    ServerSentEvent<?> done = events.get(3);

    assertThat(stepOne.event()).isEqualTo("step-event");
    assertThat(stepOne.data()).isInstanceOf(StepEvent.class);
    assertThat(((StepEvent) stepOne.data()).getStep()).isEqualTo("unified-clean-correct");

    assertThat(stepTwo.event()).isEqualTo("step-event");
    assertThat(((StepEvent) stepTwo.data()).getStep()).isEqualTo("intent-classifier");

    assertThat(finalResponse.event()).isEqualTo("prepare-response");
    assertThat(finalResponse.data()).isInstanceOf(PrepareResponse.class);
    assertThat(((PrepareResponse) finalResponse.data()).getFinalPrompt())
        .contains("sys")
        .contains("hello");

    assertThat(done.event()).isEqualTo("done");
    assertThat(done.data()).isEqualTo("done");
  }

  @Test
  void streamReturnsRateLimitErrorsAndDone() {
    PromptPipeline pipeline = mock(PromptPipeline.class);
    ThinkingStepsMapper thinkingStepsMapper = new ThinkingStepsMapper();
    PromptController controller = new PromptController(pipeline, thinkingStepsMapper);

    PrepareRequest expectedRequest = PrepareRequest.builder()
        .query("hello")
        .userId("u1")
        .sessionId("s1")
        .build();

    when(pipeline.runStreaming(expectedRequest))
        .thenReturn(Flux.error(new RateLimitException("quota exceeded")));

    List<ServerSentEvent<?>> events = controller.stream("hello", "u1", "s1").collectList().block();

    assertThat(events).isNotNull();
    assertThat(events).hasSize(2);

    ServerSentEvent<?> error = events.get(0);
    ServerSentEvent<?> done = events.get(1);

    assertThat(error.event()).isEqualTo("error");
    assertThat(error.data()).isInstanceOf(PrepareResponse.class);
    assertThat(((PrepareResponse) error.data()).getErrors())
        .containsExactly("OpenAI rate limit or quota was exceeded: quota exceeded");

    assertThat(done.event()).isEqualTo("done");
    assertThat(done.data()).isEqualTo("done");
  }
}
