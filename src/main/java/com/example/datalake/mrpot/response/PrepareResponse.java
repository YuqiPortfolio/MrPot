package com.example.datalake.mrpot.response;

import com.example.datalake.mrpot.model.StepLog;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true, fluent = false)
public class PrepareResponse {
  private String systemPrompt;
  private String userPrompt;
  private String finalPrompt;

  private String originalPrompt;
  private String currentPrompt;
  private Double changeRatio;
  private Map<String, List<String>> outline;

  private String language;
  private String intent;
  private List<String> tags;
  private Map<String, ?> entities;

  private List<StepLog> steps;
}
