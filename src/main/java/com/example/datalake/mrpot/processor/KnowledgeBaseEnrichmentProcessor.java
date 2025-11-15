package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.KnowledgeBaseMatch;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.service.KbDocumentService;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class KnowledgeBaseEnrichmentProcessor implements TextProcessor {

  private static final int DEFAULT_LIMIT = 3;

  private final KbDocumentService kbDocumentService;

  @Override
  public String name() {
    return "knowledge-base-enrichment";
  }

  @Override
  public Mono<ProcessingContext> process(ProcessingContext ctx) {
    if (ctx.isCommonResponse()) {
      return Mono.just(ctx.addStep(name(), "skip-common"));
    }

    return Mono.fromSupplier(() -> {
      String query = resolveQueryText(ctx);
      if (!StringUtils.hasText(query)) {
        ctx.setKnowledgeBaseMatches(List.of());
        return ctx.addStep(name(), "skip-empty-query");
      }

      List<KnowledgeBaseMatch> matches = kbDocumentService.searchMatches(query, DEFAULT_LIMIT);
      ctx.setKnowledgeBaseMatches(matches);
      if (matches.isEmpty()) {
        return ctx.addStep(name(), "no-matches");
      }
      return ctx.addStep(name(), "matches=" + matches.size());
    });
  }

  private String resolveQueryText(ProcessingContext ctx) {
    String prompt = PromptRenderUtils.ensureFinalPrompt(ctx);
    if (StringUtils.hasText(prompt)) {
      return prompt;
    }
    if (StringUtils.hasText(ctx.getUserPrompt())) {
      return ctx.getUserPrompt();
    }
    if (StringUtils.hasText(ctx.getNormalized())) {
      return ctx.getNormalized();
    }
    return ctx.getRawInput();
  }
}
