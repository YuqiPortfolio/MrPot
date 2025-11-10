package com.example.datalake.mrpot.model;

import lombok.*;
import lombok.experimental.Accessors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true, fluent = false)
public class StepEvent {
    private String step;
    private String note;
}
