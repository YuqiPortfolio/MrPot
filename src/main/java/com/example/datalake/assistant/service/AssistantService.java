package com.example.datalake.assistant.service;

import com.example.datalake.assistant.api.dto.ChatRequest;
import com.example.datalake.assistant.api.dto.ChatResponse;
import com.example.datalake.assistant.api.dto.DocumentFragmentDto;
import com.example.datalake.assistant.prompt.PromptPostProcessorChain;
import com.example.datalake.assistant.prompt.PromptTemplateRegistry;
import com.example.datalake.assistant.prompt.TemplateRenderer;
import com.example.datalake.kb.KbDocument;
import com.example.datalake.kb.KbSearchService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Prompt;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class AssistantService {

    private static final int DEFAULT_CONTEXT_LIMIT = 5;

    private final ChatLanguageModel chatLanguageModel;
    private final PromptTemplateRegistry templateRegistry;
    private final PromptPostProcessorChain postProcessorChain;
    private final KbSearchService kbSearchService;

    public AssistantService(
            @Nullable ChatLanguageModel chatLanguageModel,
            PromptTemplateRegistry templateRegistry,
            PromptPostProcessorChain postProcessorChain,
            KbSearchService kbSearchService) {
        this.chatLanguageModel = chatLanguageModel;
        this.templateRegistry = templateRegistry;
        this.postProcessorChain = postProcessorChain;
        this.kbSearchService = kbSearchService;
    }

    public ChatResponse chat(ChatRequest request) {
        Assert.hasText(request.question(), "Question is required");
        ensureModelConfigured();

        String language = request.language() != null ? request.language() : Locale.ENGLISH.getLanguage();
        String intent = request.intent() != null ? request.intent() : "qa";
        int limit = request.maxContextDocuments() != null ? request.maxContextDocuments() : DEFAULT_CONTEXT_LIMIT;

        List<KbDocument> documents = kbSearchService.findRelevantDocuments(request.question(), request.docType(), limit);
        PromptBundle promptBundle = buildPrompt(request.question(), language, intent, documents);
        AiMessage aiMessage = chatLanguageModel.generate(promptBundle.prompt());

        List<DocumentFragmentDto> fragments = documents.stream()
                .map(doc -> new DocumentFragmentDto(doc.getId(), doc.getDocType(), buildSnippet(doc.getContent()), doc.getMetadata()))
                .collect(Collectors.toList());

        return new ChatResponse(aiMessage.text(), fragments, promptBundle.systemPrompt(), promptBundle.userPrompt());
    }

    private PromptBundle buildPrompt(String question, String language, String intent, List<KbDocument> documents) {
        PromptTemplateRegistry.TemplateDefinition template = templateRegistry.resolveTemplate(language, intent);
        Map<String, Object> variables = new HashMap<>();
        variables.put("language", language);
        variables.put("intent", intent);
        variables.put("query", question);

        String contextBlock = documents.isEmpty()
                ? "No matching kb_documents rows were retrieved."
                : documents.stream()
                        .map(doc -> "[doc-" + doc.getId() + "] (" + doc.getDocType() + ")\n" + doc.getContent())
                        .collect(Collectors.joining("\n---\n"));
        variables.put("context", contextBlock);

        String systemPrompt = TemplateRenderer.render(template.system(), variables);
        String userPrompt = TemplateRenderer.render(template.user(), variables)
                + "\n\nContext snippets:\n"
                + contextBlock;

        PromptContext promptContext = new PromptContext(language, intent, documents);
        String processedUserPrompt = postProcessorChain.apply(userPrompt, promptContext);

        SystemMessage systemMessage = SystemMessage.from(systemPrompt);
        UserMessage userMessage = UserMessage.from(processedUserPrompt);
        Prompt prompt = Prompt.from(systemMessage, userMessage);
        return new PromptBundle(prompt, systemPrompt, processedUserPrompt);
    }

    private void ensureModelConfigured() {
        Assert.notNull(chatLanguageModel, "ChatLanguageModel bean is missing. Provide an OpenAI API key.");
    }

    private String buildSnippet(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\s+", " ").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 217) + "...";
    }
}
