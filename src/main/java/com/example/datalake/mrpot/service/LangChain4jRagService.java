package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbSnippet;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LangChain4jRagService {

    private static final int MAX_SNIPPETS = 2;
    private static final int MAX_KB_CONTEXT_CHARS = 600;
    private static final int MAX_USER_TEXT_CHARS = 320;
    private static final int MAX_MEMORY_MESSAGES = 20;

    private final ChatModel chatModel;
    private final KbSearchService kbSearchService;
    private final ConversationMemoryService conversationMemoryService;

    public Mono<ProcessingContext> generate(ProcessingContext ctx) {
        // 1) 选一个用于检索的文本（建议用已经 English-normalized 的字段）
        String userText = ctx.getNormalized();
        if (userText == null || userText.isBlank()) {
            userText = ctx.getRawInput();
        }
        if (userText == null || userText.isBlank()) {
            userText = ctx.getUserPrompt();
        }
        userText = clipForModel(userText, MAX_USER_TEXT_CHARS);
        if (userText == null || userText.isBlank()) {
            return Mono.just(ctx.addStep("langchain4j-rag", "skip-empty-text"));
        }

        String sessionId = ensureSessionId(ctx);
        List<ChatMessage> chatHistory = conversationMemoryService.getMessages(sessionId);

        List<String> keywords = ctx.getKeywords();
        if (keywords == null) {
            keywords = Collections.emptyList();
        }

        // 2) 调用「片段检索」而不是整篇文档
        List<KbSnippet> snippets = kbSearchService.searchSnippets(
                userText,
                keywords,
                MAX_SNIPPETS,
                MAX_KB_CONTEXT_CHARS
        );

        if (snippets.isEmpty()) {
            log.debug("No kb snippets matched for text='{}'", userText);
        }

        // 3) 记录涉及到的 docId（去重）
        List<Long> docIds = snippets.stream()
                .map(KbSnippet::getDocId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        ctx.setLlmDocIds(docIds);

        // 4) 在全局预算内组装 KB 上下文
        String kbContext = buildKbContext(snippets, MAX_KB_CONTEXT_CHARS);
        if (kbContext.isBlank()) {
            kbContext = "(no relevant knowledge base content found)";
        }

        // 5) 拿系统 prompt（由前面 Processor 链构建）
        String systemPrompt = PromptRenderUtils.ensureSystemPrompt(ctx);

        // 6) 拼装 chat history + 最终 prompt（单条 string，规模可控）
        String chatHistoryBlock = renderChatHistory(chatHistory);
        String finalPromptForLlm = """
%s
Previous conversation (most recent first):
%s
Answer using the KB if it helps. If not relevant, say: "Sorry, I can only reply to Yuqi Guo's related content."
KB:
%s
Question:
%s
Keep it concise.
""".formatted(systemPrompt, chatHistoryBlock, kbContext, userText);

        log.debug("LangChain4jRagService: finalPrompt len={} chars", finalPromptForLlm.length());

        final String stepInfo = "ok snippets=" + snippets.size()
                + ", docs=" + docIds.size()
                + ", kbChars=" + kbContext.length();

        final String promptForLlm = finalPromptForLlm;
        final ProcessingContext ctxRef = ctx;

        // 7) 调 LangChain4j（同步封装成 Mono）
        return Mono.fromCallable(() -> {
            String answer = chatModel.chat(promptForLlm);
            conversationMemoryService.appendTurn(sessionId, userText, answer);
            ctxRef.setLlmAnswer(answer);
            return ctxRef.addStep("langchain4j-rag", stepInfo);
        });
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

    private String ensureSessionId(ProcessingContext ctx) {
        if (ctx.getSessionId() == null || ctx.getSessionId().isBlank()) {
            ctx.setSessionId(UUID.randomUUID().toString());
        }
        return ctx.getSessionId();
    }

    private String renderChatHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(no previous messages)";
        }

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, messages.size() - MAX_MEMORY_MESSAGES);
        for (int i = messages.size() - 1; i >= start; i--) {
            ChatMessage message = messages.get(i);
            String role;
            String content;

            if (message instanceof UserMessage userMessage) {
                role = "User";
                content = userMessage.singleText();
            } else if (message instanceof AiMessage aiMessage) {
                role = "Assistant";
                content = aiMessage.text();
            } else if (message instanceof SystemMessage systemMessage) {
                role = "System";
                content = systemMessage.text();
            } else {
                role = message.getClass().getSimpleName();
                content = message.toString();
            }

            sb.append("- ").append(role).append(": ").append(content).append("\n");
        }

        return sb.toString().stripTrailing();
    }
}
