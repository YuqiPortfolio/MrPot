package com.example.datalake.mrpot.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.experimental.Accessors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true, fluent = false)
public class PrepareRequest {
    private String userId;
    private String sessionId;
    @NotBlank
    private String query;
}
