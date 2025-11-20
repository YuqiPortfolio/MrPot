package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbSnippet;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Use processors' result + kb_documents (in Supabase) to call LangChain4j ChatModel.
 * This is the RAG layer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LangChain4jRagService {

    private static final int MAX_SNIPPETS = 8;
    private static final int MAX_KB_CONTEXT_CHARS = 6000;

    private final ChatModel chatModel;
    private final KbSearchService kbSearchService;

    public Mono<ProcessingContext> generate(ProcessingContext ctx) {
        // 1) Pick a "user text" as retrieval query (prefer userPrompt, then finalPrompt)
        String userText = ctx.getUserPrompt();
        if (userText == null || userText.isBlank()) {
            userText = ctx.getFinalPrompt();
        }

        if (userText == null || userText.isBlank()) {
            // No user text → fall back to plain prompt call
            String plainPrompt = PromptRenderUtils.ensureFinalPrompt(ctx);
            return Mono.fromCallable(() -> {
                String answer = chatModel.chat(plainPrompt);
                ctx.setLlmAnswer(answer);
                return ctx.addStep("langchain4j-rag", "no user text, skip kb");
            });
        }

        // 2) Text used for semantic search (usually cleaned/translated text)
        String queryText = Optional.ofNullable(ctx.getIndexText()).orElse(userText);
        List<String> keywords = Optional.ofNullable(ctx.getKeywords()).orElse(List.of());

        log.debug("RAG queryText='{}', keywords={}", queryText, keywords);

        // 3) Query Supabase KB via pgvector
        List<KbSnippet> snippets = kbSearchService.searchSnippets(
                queryText,
                keywords,
                MAX_SNIPPETS
        );

        // 4) Build KB context string (with max length)
        String kbContext = snippets.stream()
                .map(KbSnippet::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        if (kbContext.length() > MAX_KB_CONTEXT_CHARS) {
            kbContext = kbContext.substring(0, MAX_KB_CONTEXT_CHARS);
        }

        if (kbContext.isBlank()) {
            kbContext = "(no matched knowledge base snippets)";
        }

        // record doc ids
        ctx.setLlmDocIds(snippets.stream().map(KbSnippet::getId).toList());

        // 5) Render final prompt for LLM using your own helper
        String finalPromptForLlm = PromptRenderUtils.renderRagPrompt(
                ctx.getSystemPrompt(),
                userText,
                kbContext,
                ctx.getLanguage(),
                ctx.getIntent(),
                keywords
        );
        ctx.setFinalPrompt(finalPromptForLlm);

        // 6) Call LangChain4j ChatModel (blocking call wrapped in Mono)
        String finalUserText = userText;
        String finalKbContext = kbContext;

        return Mono.fromCallable(() -> {
            String answer = chatModel.chat(finalPromptForLlm);
            ctx.setLlmAnswer(answer);
            return ctx.addStep(
                    "langchain4j-rag",
                    "ok snippets=" + snippets.size()
                            + ", kbChars=" + finalKbContext.length()
                            + ", userTextLen=" + finalUserText.length()
            );
        });
    }
}
