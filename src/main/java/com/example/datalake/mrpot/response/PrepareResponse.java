package com.example.datalake.mrpot.response;

import com.example.datalake.mrpot.model.StepLog;
import lombok.*;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true, fluent = false)
public class PrepareResponse {
    private String systemPrompt;
    private String userPrompt;
    private String finalPrompt;

    private String language;
    private String intent;
    private List<String> tags;
    private Map<String, ?> entities;

    private List<StepLog> steps;
}
