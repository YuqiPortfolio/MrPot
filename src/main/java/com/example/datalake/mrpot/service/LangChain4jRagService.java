package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbDocument;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Use processors' result + kb_documents to call LangChain4j ChatModel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LangChain4jRagService {

    private static final int MAX_KB_DOCS = 2;
    private static final int MAX_DOC_CHARS = 1200;   // truncate each doc

    private final ChatModel chatModel;
    private final KbSearchService kbSearchService;

    public Mono<ProcessingContext> generate(ProcessingContext ctx) {
        // 1) pick "user text" for retrieval (userPrompt > finalPrompt > rawInput)
        String userText = ctx.getUserPrompt();
        if (userText == null || userText.isBlank()) {
            userText = ctx.getFinalPrompt();
        }
        if (userText == null || userText.isBlank()) {
            userText = ctx.getRawInput();
        }
        if (userText == null || userText.isBlank()) {
            return Mono.just(ctx.addStep("langchain4j-rag", "skip-empty-text"));
        }

        // 2) search Supabase kb_documents
        List<KbDocument> matchedDocs = kbSearchService.search(userText, ctx.getKeywords());
        List<KbDocument> docs = matchedDocs.stream()
                .limit(MAX_KB_DOCS)
                .toList();

        if (docs.isEmpty()) {
            log.debug("No kb_documents matched for text='{}'", userText);
        }

        // 3) build context block (truncated)
        String kbContext = docs.stream()
                .map(doc -> truncate(doc.getContent(), MAX_DOC_CHARS))
                .collect(Collectors.joining("\n\n---\n\n"));

        if (kbContext.isBlank()) {
            kbContext = "(no relevant knowledge base content found)";
        }

        // record doc ids
        ctx.setLlmDocIds(docs.stream().map(KbDocument::getId).toList());

        // 4) base system prompt from your pipeline
        String systemPrompt = PromptRenderUtils.ensureSystemPrompt(ctx);

        // 5) short final prompt
        String finalPromptForLlm = """
%s

You also have some knowledge base snippets (they may be incomplete):

%s

Use BOTH:
- your general world knowledge and reasoning, and
- the knowledge base above.

If they ever conflict, treat the knowledge base as the source of truth.
Do **not** invent facts that contradict the knowledge base.
Only say "I don't know" when the answer cannot be reasonably inferred
from your general knowledge and the knowledge base.

Question (same language in your answer):
%s

Answer briefly, in the same language as the question:
""".formatted(systemPrompt, kbContext, userText);

        log.debug("LangChain4jRagService: finalPromptForLlm=\n{}", finalPromptForLlm);

        // 6) sync call wrapped in Mono
        return Mono.fromCallable(() -> {
            String answer = chatModel.chat(finalPromptForLlm);
            ctx.setLlmAnswer(answer);
            return ctx.addStep("langchain4j-rag",
                    "ok docs=" + docs.size() + "/matched=" + matchedDocs.size());
        });
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...";
    }
}
