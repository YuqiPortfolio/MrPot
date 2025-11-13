package com.example.datalake.mrpot.model;

import java.time.Instant;
import lombok.*;
import lombok.experimental.Accessors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true, fluent = false)
public class StepLog {
  private String name;
  private String note;
  private Instant at;
}
