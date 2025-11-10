package com.example.datalake.mrpot.prompt.service;

import com.example.datalake.mrpot.prompt.api.PrepareRequest;
import com.example.datalake.mrpot.prompt.api.PrepareResponse;
import com.example.datalake.mrpot.prompt.api.PromptSessionAttributes;
import com.example.datalake.mrpot.prompt.config.PromptProperties;
import com.example.datalake.mrpot.prompt.domain.StepEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PromptService {

    private final PromptProperties properties;

    public PromptService(PromptProperties properties) {
        this.properties = properties;
    }

    public PrepareResponse prepareSession(PrepareRequest request) {
        String resolvedUserId = Optional.ofNullable(request.userId())
                .filter(id -> !id.isBlank())
                .orElse("anonymous");
        String resolvedSessionId = Optional.ofNullable(request.sessionId())
                .filter(id -> !id.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());

        String systemPrompt = properties.getSystemPrompt().strip();
        String userPrompt = "User(" + resolvedUserId + "): " + request.query();
        String finalPrompt = systemPrompt + "\n---\n" + userPrompt;

        PromptSessionAttributes session = new PromptSessionAttributes(
                resolvedUserId,
                resolvedSessionId,
                request.query()
        );

        return new PrepareResponse(
                systemPrompt,
                userPrompt,
                finalPrompt,
                properties.getLanguage(),
                properties.getIntent(),
                properties.getTags(),
                session,
                List.of()
        );
    }

    public Flux<StepEvent> streamStepEvents(String query, String userId, String sessionId) {
        List<String> steps = properties.getStream().getSteps();
        Duration delay = properties.getStream().getDelay();
        String resolvedUserId = Optional.ofNullable(userId)
                .filter(id -> !id.isBlank())
                .orElse("anonymous");
        String resolvedSessionId = Optional.ofNullable(sessionId)
                .filter(id -> !id.isBlank())
                .orElse("n/a");

        return Flux.interval(delay)
                .take(steps.size())
                .map(index -> {
                    int ordinal = index.intValue() + 1;
                    String step = steps.get(index.intValue());
                    String note = "Processed step " + ordinal + " for user '" + resolvedUserId +
                            "' (session " + resolvedSessionId + ") with query '" + query + "'";
                    return new StepEvent(ordinal, step, note);
                });
    }
}
