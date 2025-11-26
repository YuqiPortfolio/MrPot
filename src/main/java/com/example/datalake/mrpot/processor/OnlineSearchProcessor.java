package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.KbSnippet;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.service.KbSearchService;
import com.example.datalake.mrpot.service.LangChain4jRagService;
import com.example.datalake.mrpot.service.OnlineSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OnlineSearchProcessor implements TextProcessor {

    private final KbSearchService kbSearchService;
    private final OnlineSearchService onlineSearchService;

    @Override
    public String name() {
        return "online-search";
    }

    @Override
    public Mono<ProcessingContext> process(ProcessingContext ctx) {
        List<String> keywords = Optional.ofNullable(ctx.getKeywords()).orElse(Collections.emptyList());
        if (keywords.isEmpty()) {
            return Mono.just(ctx.addStep(name(), "skip-no-keywords"));
        }

        String searchText = LangChain4jRagService.selectUserText(ctx);
        if (searchText == null || searchText.isBlank()) {
            return Mono.just(ctx.addStep(name(), "skip-empty-query"));
        }

        List<KbSnippet> existing = Optional.ofNullable(ctx.getKbSnippets()).orElse(Collections.emptyList());
        if (!existing.isEmpty()) {
            return Mono.just(ctx.addStep(name(), "skip-kb-hit"));
        }

        List<KbSnippet> kbSnippets = kbSearchService.searchSnippets(
                searchText,
                keywords,
                LangChain4jRagService.MAX_SNIPPETS,
                LangChain4jRagService.MAX_KB_CONTEXT_CHARS
        );
        ctx.setKbSnippets(kbSnippets);

        if (!kbSnippets.isEmpty()) {
            return Mono.just(ctx.addStep(name(), "kb-hit=" + kbSnippets.size()));
        }

        return onlineSearchService.searchConciseReferences(searchText, keywords)
                .map(refs -> {
                    String trimmed = refs == null ? "" : refs.strip();
                    if (trimmed.isBlank()) {
                        return ctx.addStep(name(), "online-empty");
                    }
                    ctx.setOnlineReferences(trimmed);
                    return ctx.addStep(name(), "online-fallback");
                });
    }
}
