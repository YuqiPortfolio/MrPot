package com.example.datalake.mrpot.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

public record KbDocumentRequest(
        @NotBlank(message = "Document type is required") String docType,
        @NotBlank(message = "Content is required") String content,
        JsonNode metadata
) {
}
