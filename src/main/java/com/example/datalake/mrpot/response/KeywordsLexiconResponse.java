package com.example.datalake.mrpot.response;

import java.time.OffsetDateTime;
import java.util.List;

public record KeywordsLexiconResponse(
        String canonical,
        List<String> synonyms,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
