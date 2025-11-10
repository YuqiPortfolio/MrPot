package com.example.datalake.mrpot.service;

import static com.github.pemistahl.lingua.api.Language.ENGLISH;
import static org.junit.jupiter.api.Assertions.*;

import com.example.datalake.mrpot.processor.LanguageDetectorProcessor;
import com.example.datalake.mrpot.processor.UnifiedCleanCorrectProcessor;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareResponse;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PromptPipelineTest {
  private static LanguageDetector detector;

  @BeforeAll
  static void setUpDetector() {
    detector = LanguageDetectorBuilder.fromLanguages(ENGLISH).build();
  }

  @Test
  void prepareUsesRevisedPromptInResponse() {
    UnifiedCleanCorrectProcessor clean = new UnifiedCleanCorrectProcessor();
    LanguageDetectorProcessor language = new LanguageDetectorProcessor(detector);
    PromptPipeline pipeline = new PromptPipeline(clean, language);

    PrepareRequest request =
        PrepareRequest.builder()
            .userId("alice")
            .query("i need teh quarterly revenue report")
            .build();

    PrepareResponse response = pipeline.prepare(request).block();
    assertNotNull(response);
    assertEquals(request.getQuery(), response.getOriginalPrompt());
    assertNotNull(response.getCurrentPrompt());
    assertTrue(response.getCurrentPrompt().startsWith("I need"));
    assertTrue(response.getUserPrompt().contains(response.getCurrentPrompt()));
    assertTrue(response.getFinalPrompt().contains(response.getCurrentPrompt()));
    assertEquals("en", response.getLanguage());
    assertEquals("alice", response.getEntities().get("userId"));
    assertNotNull(response.getSteps());
    assertFalse(response.getSteps().isEmpty(), "Pipeline should record steps");
  }
}
