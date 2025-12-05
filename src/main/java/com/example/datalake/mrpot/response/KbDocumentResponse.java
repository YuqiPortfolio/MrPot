package com.example.datalake.mrpot.response;

import com.fasterxml.jackson.databind.JsonNode;

public record KbDocumentResponse(
        Long id,
        String docType,
        String content,
        JsonNode metadata
) {
}
