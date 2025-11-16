package com.example.datalake.assistant.prompt;

import com.example.datalake.assistant.service.PromptContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class ContextSafetyPromptProcessor implements PromptPostProcessor {

    @Override
    public String process(String prompt, PromptContext context) {
        StringBuilder builder = new StringBuilder(prompt);
        builder.append("\n\nInstructions:\n");
        if (context.contextDocuments().isEmpty()) {
            builder.append("- No verified knowledge base context was found. Answer only if you are confident, otherwise say you do not know.\n");
        } else {
            builder.append("- Only rely on the provided context snippets. If the context does not contain the answer, explicitly say so.\n");
        }
        builder.append("- Cite document ids when referencing the knowledge base (e.g., [doc-123]).");
        return builder.toString();
    }
}
