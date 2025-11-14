package com.example.datalake.mrpot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.validation.MaxCharsValidator;
import com.example.datalake.mrpot.validation.NotBlankInputValidator;
import com.example.datalake.mrpot.validation.TemplatePlaceholderValidator;
import com.example.datalake.mrpot.validation.ValidationException;
import com.example.datalake.mrpot.validation.ValidationService;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptPipelineValidationTest {

  private PromptPipeline newPipeline(int maxChars) {
    ValidationService validationService = new ValidationService(
        List.of(
            new NotBlankInputValidator(),
            new MaxCharsValidator(maxChars),
            new TemplatePlaceholderValidator()));
    return new PromptPipeline(List.of(), validationService);
  }

  @Test
  void runRejectsBlankQueries() {
    PromptPipeline pipeline = newPipeline(10);
    PrepareRequest request = new PrepareRequest().setQuery("   ");

    assertThatThrownBy(() -> pipeline.run(request).block())
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void runAppliesValidationMutations() {
    PromptPipeline pipeline = newPipeline(5);
    PrepareRequest request = new PrepareRequest().setQuery("abcdefghij");

    ProcessingContext context = pipeline.run(request).block();
    assertThat(context).isNotNull();
    assertThat(context.getRawInput()).isEqualTo("abcde");
    assertThat(context.getSystemPrompt()).contains("{{CONTEXT}}");
    assertThat(context.getValidationNotices())
        .containsExactly(
            "Input truncated to 5 characters to satisfy platform limits.",
            "Injected missing {{CONTEXT}} placeholder into system prompt.");
  }
}
