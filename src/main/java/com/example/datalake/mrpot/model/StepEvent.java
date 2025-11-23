package com.example.datalake.mrpot.model;

import lombok.*;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true, fluent = false)
public class StepEvent {
  private String step;
  private String note;
  private Integer progress;
  private String status;
  private Map<String, Object> payload;
}
