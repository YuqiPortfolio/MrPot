package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.processor.LanguageDetectorProcessor;
import com.example.datalake.mrpot.processor.UnifiedCleanCorrectProcessor;
import com.example.datalake.mrpot.request.PrepareRequest;
import com.example.datalake.mrpot.response.PrepareResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class PromptPipeline {
  private static final String SYSTEM_PROMPT =
      "You are MrPot, a helpful data-lake assistant. Keep answers concise.";

  private final UnifiedCleanCorrectProcessor cleanCorrectProcessor;
  private final LanguageDetectorProcessor languageDetectorProcessor;

  public Mono<PrepareResponse> prepare(PrepareRequest request) {
    ProcessingContext context =
        new ProcessingContext()
            .setUserId(request.getUserId())
            .setSessionId(resolveSessionId(request.getSessionId()))
            .setRawInput(request.getQuery())
            .setNow(Instant.now());

    return cleanCorrectProcessor
        .process(context)
        .flatMap(languageDetectorProcessor::process)
        .map(processed -> assembleResponse(request, processed));
  }

  private PrepareResponse assembleResponse(PrepareRequest request, ProcessingContext context) {
    String revised =
        Optional.ofNullable(context.getCorrected())
            .filter(s -> !s.isBlank())
            .orElse(request.getQuery());
    String userLabel =
        Optional.ofNullable(context.getUserId()).filter(s -> !s.isBlank()).orElse("anonymous");
    String userPrompt = "User(" + userLabel + "): " + revised;
    String finalPrompt = SYSTEM_PROMPT + "\n---\n" + userPrompt;

    context.setSystemPrompt(SYSTEM_PROMPT);
    context.setUserPrompt(userPrompt);
    context.setFinalPrompt(finalPrompt);
    context.addStep("prompt-assemble", "finalPromptLen=" + finalPrompt.length());

    Map<String, Object> entityPayload = new LinkedHashMap<>();
    entityPayload.put("userId", context.getUserId());
    entityPayload.put("sessionId", context.getSessionId());
    entityPayload.put("query", request.getQuery());
    if (context.getLanguage() != null) {
      entityPayload.put("language", context.getLanguage().getIsoCode());
      entityPayload.put("languageDisplay", context.getLanguage().getDisplayName());
      entityPayload.put("languageConfidence", context.getLanguage().getConfidence());
    }
    if (context.getIndexText() != null) {
      entityPayload.put("indexText", context.getIndexText());
      entityPayload.put("indexLanguage", context.getIndexLanguage());
    }
    if (context.getEntities() != null && !context.getEntities().isEmpty()) {
      entityPayload.put("detectedEntities", context.getEntities());
    }

    List<String> tags =
        context.getTags() == null || context.getTags().isEmpty()
            ? List.of()
            : new ArrayList<>(context.getTags());

    Map<String, List<String>> outline =
        context.getOutline() == null || context.getOutline().isEmpty()
            ? Map.of()
            : new LinkedHashMap<>(context.getOutline());

    String languageCode =
        context.getLanguage() == null
            ? Language.und().getIsoCode()
            : context.getLanguage().getIsoCode();
    String intent =
        context.getIntent() == null
            ? "unknown"
            : context.getIntent().name().toLowerCase(Locale.ROOT);

    return PrepareResponse.builder()
        .systemPrompt(SYSTEM_PROMPT)
        .userPrompt(userPrompt)
        .finalPrompt(finalPrompt)
        .originalPrompt(request.getQuery())
        .currentPrompt(revised)
        .changeRatio(context.getChangeRatio())
        .outline(outline)
        .language(languageCode)
        .intent(intent)
        .tags(tags)
        .entities(entityPayload)
        .steps(context.getSteps() == null ? List.of() : List.copyOf(context.getSteps()))
        .build();
  }

  private String resolveSessionId(String sessionId) {
    return Optional.ofNullable(sessionId)
        .filter(s -> !s.isBlank())
        .orElseGet(() -> UUID.randomUUID().toString());
  }
}
