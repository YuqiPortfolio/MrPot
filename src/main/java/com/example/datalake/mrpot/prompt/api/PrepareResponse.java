package com.example.datalake.mrpot.prompt.api;

import com.example.datalake.mrpot.prompt.domain.StepLog;

import java.util.List;
import java.util.Objects;

public record PrepareResponse(
        String systemPrompt,
        String userPrompt,
        String finalPrompt,
        String language,
        String intent,
        List<String> tags,
        PromptSessionAttributes session,
        List<StepLog> steps
) {
    public PrepareResponse {
        tags = tags == null ? List.of() : List.copyOf(tags);
        session = Objects.requireNonNull(session, "session");
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
