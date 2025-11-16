package com.example.datalake.assistant.service;

import dev.langchain4j.data.message.Prompt;

public record PromptBundle(Prompt prompt, String systemPrompt, String userPrompt) {}
