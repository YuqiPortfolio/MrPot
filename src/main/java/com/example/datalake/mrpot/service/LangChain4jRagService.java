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

    private final ChatModel chatModel;
    private final KbSearchService kbSearchService;

    public Mono<ProcessingContext> generate(ProcessingContext ctx) {
        // 1) 选一个 “用户文本” 作为检索条件（优先 finalPrompt/userPrompt）
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

        // 2) 查 Supabase kb_documents
        List<KbDocument> docs = kbSearchService.searchByUserText(userText);
        if (docs.isEmpty()) {
            log.debug("No kb_documents matched for text='{}'", userText);
        }

        // 3) 拼成 context block
        String kbContext = docs.stream()
                .map(KbDocument::getContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 记录一下用到哪些文档 id，便于调试 / 展示
        ctx.setLlmDocIds(docs.stream().map(KbDocument::getId).toList());

        // 4) system prompt：用你已有的 PromptRenderUtils
        String systemPrompt = PromptRenderUtils.ensureSystemPrompt(ctx);

        // 5) 最终送给 LLM 的 prompt
        String finalPromptForLlm = """
                %s

                You must answer STRICTLY based on the following knowledge base.
                If the answer is not contained there, say you don't know.

                ### Knowledge Base
                %s

                ### User Input
                %s
                """.formatted(
                systemPrompt != null ? systemPrompt : "",
                kbContext.isBlank() ? "(no relevant kb_documents found)" : kbContext,
                userText
        );

        log.debug("LangChain4jRagService: finalPromptForLlm=\n{}", finalPromptForLlm);

        // 6) 同步调用 ChatModel（封在 Mono.fromCallable 里）
        return Mono.fromCallable(() -> {
            String answer = chatModel.chat(finalPromptForLlm);
            ctx.setLlmAnswer(answer);
            return ctx.addStep("langchain4j-rag", "ok docs=" + docs.size());
        });
    }
}
