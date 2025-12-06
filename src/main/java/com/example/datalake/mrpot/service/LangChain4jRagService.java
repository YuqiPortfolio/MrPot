package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbSnippet;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class LangChain4jRagService {

    private static final int MAX_SNIPPETS = 2;
    private static final int MAX_KB_CONTEXT_CHARS = 480;
    private static final int MAX_USER_TEXT_CHARS = 260;
    static final String NO_KB_CONTEXT_PLACEHOLDER = "(no relevant knowledge base content found)";

    private final ChatModel chatModel;
    private final KbSearchService kbSearchService;

    public Mono<ProcessingContext> prepare(ProcessingContext ctx) {
        String userText = resolveUserText(ctx);
        if (isBlank(userText)) {
            return Mono.just(ctx.addStep("langchain4j-rag", "skip-empty-text"));
        }

        List<String> keywords = safeKeywords(ctx.getKeywords());
        if (keywords.isEmpty()) {
            return Mono.just(ctx.addStep("langchain4j-rag", "skip-empty-keywords"));
        }

        // 1) 调用「片段检索」而不是整篇文档
        List<KbSnippet> snippets = kbSearchService.searchSnippets(
                userText,
                keywords,
                MAX_SNIPPETS,
                MAX_KB_CONTEXT_CHARS
        );

        if (snippets.isEmpty()) {
            log.debug("No kb snippets matched for text='{}'", userText);
        }

        // 2) 记录涉及到的 docId（去重）
        List<Long> docIds = extractDocIds(snippets);
        ctx.setLlmDocIds(docIds);

        // 3) 在全局预算内组装 KB 上下文
        String kbContext = buildKbContext(snippets, MAX_KB_CONTEXT_CHARS);
        if (kbContext.isBlank()) {
            kbContext = NO_KB_CONTEXT_PLACEHOLDER;
        }

        ctx.setKbContext(kbContext);
        ctx.setLlmQuestion(userText);
        ctx.setKbSnippetCount(snippets.size());

        // 4) 拿系统 prompt（由前面 Processor 链构建）
        PromptRenderUtils.ensureSystemPrompt(ctx);

        return Mono.just(ctx);
    }

    public Mono<ProcessingContext> completeWithLlm(ProcessingContext ctx, String stepInfo) {
        final String promptForLlm = safe(ctx.getFinalPrompt());
        if (isBlank(promptForLlm)) {
            return Mono.just(ctx.addStep("langchain4j-rag", "skip-empty-final-prompt"));
        }

        final ProcessingContext ctxRef = ctx;
        return Mono.fromCallable(() -> {
            String answer = chatModel.chat(promptForLlm);
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

    private static String resolveUserText(ProcessingContext ctx) {
        String userText = firstNonBlank(ctx.getUserPrompt(), ctx.getNormalized(), ctx.getRawInput());
        return clipForModel(userText, MAX_USER_TEXT_CHARS);
    }

    private static List<String> safeKeywords(List<String> keywords) {
        return keywords == null ? Collections.emptyList() : keywords;
    }

    private static List<Long> extractDocIds(List<KbSnippet> snippets) {
        return snippets.stream()
                .map(KbSnippet::getDocId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... ss) {
        for (String s : ss) {
            if (!isBlank(s)) {
                return s;
            }
        }
        return null;
    }
}
