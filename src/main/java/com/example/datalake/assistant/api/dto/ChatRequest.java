package com.example.datalake.assistant.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "question is required") String question,
        String intent,
        String language,
        String docType,
        Integer maxContextDocuments) {}
