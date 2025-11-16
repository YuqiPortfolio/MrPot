package com.example.datalake.assistant.api.dto;

import java.util.List;

public record ChatResponse(String answer, List<DocumentFragmentDto> context, String systemPrompt, String userPrompt) {}
