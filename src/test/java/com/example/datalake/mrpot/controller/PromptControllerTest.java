package com.example.datalake.mrpot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareEnvelope;
import com.example.datalake.mrpot.response.PrepareResponse;
import com.example.datalake.mrpot.service.PromptPipeline;
import com.example.datalake.mrpot.validation.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

class PromptControllerTest {

  @Test
  void prepareReturnsValidationErrors() {
    PromptPipeline pipeline = mock(PromptPipeline.class);
    PrepareRequest request = new PrepareRequest().setQuery("   ");

    ValidationException exception = new ValidationException("User input must not be blank.");
    when(pipeline.run(request)).thenReturn(Mono.error(exception));

    PromptController controller = new PromptController(pipeline);

    ResponseEntity<PrepareEnvelope> response = controller.prepare(request).block();

    assertThat(response).isNotNull();
    assertThat(response.getStatusCodeValue()).isEqualTo(400);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getWhy()).isNotNull();
    assertThat(response.getBody().getWhy().getErrors())
        .containsExactly("User input must not be blank.");
    assertThat(response.getBody().getWhy().getNotices()).isEmpty();
  }
}
