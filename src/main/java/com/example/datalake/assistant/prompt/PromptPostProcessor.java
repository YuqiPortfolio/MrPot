package com.example.datalake.assistant.prompt;

import com.example.datalake.assistant.service.PromptContext;

public interface PromptPostProcessor {
    String process(String prompt, PromptContext context);
}
