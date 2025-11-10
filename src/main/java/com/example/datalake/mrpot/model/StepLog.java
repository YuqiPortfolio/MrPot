package com.example.datalake.mrpot.model;

import lombok.*;
import lombok.experimental.Accessors;

import java.time.Instant;

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
