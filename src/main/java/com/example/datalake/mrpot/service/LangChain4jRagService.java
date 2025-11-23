package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbSnippet;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LangChain4jRagService {

    private static final int MAX_SNIPPETS = 2;
    private static final int MAX_KB_CONTEXT_CHARS = 600;
    private static final int MAX_USER_TEXT_CHARS = 320;
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final ChatModel chatModel;
    private final KbSearchService kbSearchService;

    private final ConcurrentMap<String, ChatMemory> chatMemories = new ConcurrentHashMap<>();

    public Mono<ProcessingContext> generate(ProcessingContext ctx) {
        return preparePrompt(ctx)
                .flatMap(this::runChat)
                .thenReturn(ctx);
    }

    public Flux<String> streamWithMemory(ProcessingContext ctx) {
        return preparePrompt(ctx)
                .flatMapMany(prompt -> runChat(prompt)
                        .map(this::chunkAnswer)
                        .flatMapMany(Flux::fromIterable));
    }

    private Mono<LlmPrompt> preparePrompt(ProcessingContext ctx) {
        String sessionId = ensureSessionId(ctx);
        String userText = resolveUserText(ctx);

        if (userText == null || userText.isBlank()) {
            return Mono.just(new LlmPrompt(ctx.addStep("langchain4j-rag", "skip-empty-text"),
                    sessionId, "", "", "", "skip-empty-text", chatMemory(sessionId), true));
        }

        List<String> keywords = ctx.getKeywords() == null ? Collections.emptyList() : ctx.getKeywords();

        List<KbSnippet> snippets = kbSearchService.searchSnippets(
                userText,
                keywords,
                MAX_SNIPPETS,
                MAX_KB_CONTEXT_CHARS
        );

        if (snippets.isEmpty()) {
            log.debug("No kb snippets matched for text='{}'", userText);
        }

        List<Long> docIds = snippets.stream()
                .map(KbSnippet::getDocId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        ctx.setLlmDocIds(docIds);

        String kbContext = buildKbContext(snippets, MAX_KB_CONTEXT_CHARS);
        if (kbContext.isBlank()) {
            kbContext = "(no relevant knowledge base content found)";
        }

        ChatMemory memory = chatMemory(sessionId);
        String systemPrompt = PromptRenderUtils.ensureSystemPrompt(ctx);
        String userPrompt = formatUserPrompt(userText, kbContext);

        log.debug("LangChain4jRagService: userPrompt len={} chars", userPrompt.length());

        String stepInfo = "ok snippets=" + snippets.size()
                + ", docs=" + docIds.size()
                + ", kbChars=" + kbContext.length();

        return Mono.just(new LlmPrompt(ctx, sessionId, systemPrompt, userPrompt, userText, stepInfo, memory, false));
    }

    private Mono<String> runChat(LlmPrompt prompt) {
        if (prompt.skip()) {
            return Mono.fromCallable(() -> {
                prompt.ctx().addStep("langchain4j-rag", prompt.stepInfo());
                return "";
            });
        }

        return Mono.fromCallable(() -> {
            ChatMemory memory = prompt.memory();
            ensureSystemMessage(memory, prompt.systemPrompt());

            List<ChatMessage> messages = new ArrayList<>(memory.messages());
            messages.add(UserMessage.from(prompt.userPrompt()));

            Response<AiMessage> response = chatModel.generate(messages);
            AiMessage answer = response.content();

            finalizeConversation(prompt, prompt.userPrompt(), answer);
            return answer.text();
        });
    }

    private void finalizeConversation(LlmPrompt prompt, String userPrompt, AiMessage answer) {
        prompt.memory().add(UserMessage.from(userPrompt));
        prompt.memory().add(answer);

        prompt.ctx().setLlmAnswer(answer.text());
        prompt.ctx().addStep("langchain4j-rag", prompt.stepInfo());
    }

    private List<String> chunkAnswer(String answer) {
        if (answer == null || answer.isEmpty()) {
            return List.of();
        }
        return List.of(answer.split("(?<=\\s)"));
    }

    private String resolveUserText(ProcessingContext ctx) {
        String userText = ctx.getNormalized();
        if (userText == null || userText.isBlank()) {
            userText = ctx.getRawInput();
        }
        if (userText == null || userText.isBlank()) {
            userText = ctx.getUserPrompt();
        }
        return clipForModel(userText, MAX_USER_TEXT_CHARS);
    }

    private String ensureSessionId(ProcessingContext ctx) {
        if (ctx.getSessionId() == null || ctx.getSessionId().isBlank()) {
            String sessionId = java.util.UUID.randomUUID().toString();
            ctx.setSessionId(sessionId);
            return sessionId;
        }
        return ctx.getSessionId();
    }

    private ChatMemory chatMemory(String sessionId) {
        return chatMemories.computeIfAbsent(sessionId, id -> MessageWindowChatMemory.withMaxMessages(MAX_HISTORY_MESSAGES));
    }

    private void ensureSystemMessage(ChatMemory memory, String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return;
        }
        memory.add(SystemMessage.from(systemPrompt));
    }

    /**
     * 在指定总预算内组装 KB 文本。
     * 这里只做非常轻量的格式化，复杂逻辑（怎么抽 snippet）放在 KbSearchService 里。
     */
    private static String buildKbContext(List<KbSnippet> snippets, int maxTotalChars) {
        if (snippets == null || snippets.isEmpty() || maxTotalChars <= 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int remaining = maxTotalChars;

        for (int i = 0; i < snippets.size() && remaining > 0; i++) {
            KbSnippet s = snippets.get(i);
            if (s == null) continue;

            String title = safe(s.getTitle());
            String source = safe(s.getSource());
            String snippet = normalizeWhitespace(safe(s.getSnippet()));

            if (snippet.isBlank()) continue;

            String header = "[#" + (i + 1) + "]"
                    + (title.isBlank() ? "" : " " + title)
                    + (source.isBlank() ? "" : " " + source);

            String block = header + "\n" + snippet;
            if (sb.length() > 0) {
                block = "\n\n" + block;
            }

            if (block.length() > remaining) {
                // 超预算的话，最后一个片段也截一下
                block = block.substring(0, remaining) + "...";
            }

            sb.append(block);
            remaining -= block.length();
        }

        return sb.toString();
    }

    private static String clipForModel(String text, int maxChars) {
        if (text == null) return null;
        String trimmed = text.strip();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, maxChars) + "...";
    }

    private static String normalizeWhitespace(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private String formatUserPrompt(String userText, String kbContext) {
        String effectiveKb = kbContext == null || kbContext.isBlank()
                ? "(no relevant knowledge base content found)"
                : kbContext;
        return """
Answer using the KB if it helps. If not relevant, say: "Sorry, I can only reply to Yuqi Guo's related content." 
KB:
%s
Question:
%s
Keep it concise.
""".formatted(effectiveKb, userText);
    }

    private record LlmPrompt(
            ProcessingContext ctx,
            String sessionId,
            String systemPrompt,
            String userPrompt,
            String userText,
            String stepInfo,
            ChatMemory memory,
            boolean skip
    ) {
    }
}
