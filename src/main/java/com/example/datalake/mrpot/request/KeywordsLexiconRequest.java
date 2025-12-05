package com.example.datalake.mrpot.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record KeywordsLexiconRequest(
        @NotBlank(message = "Canonical keyword is required") String canonical,
        List<String> synonyms,
        Boolean active
) {
}
