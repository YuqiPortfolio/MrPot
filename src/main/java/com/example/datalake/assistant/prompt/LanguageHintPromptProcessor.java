package com.example.datalake.assistant.prompt;

import com.example.datalake.assistant.service.PromptContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class LanguageHintPromptProcessor implements PromptPostProcessor {

    @Override
    public String process(String prompt, PromptContext context) {
        return prompt + "\n- Respond in " + context.language() + ".";
    }
}
