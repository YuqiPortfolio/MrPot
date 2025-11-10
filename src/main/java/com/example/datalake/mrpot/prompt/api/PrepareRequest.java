package com.example.datalake.mrpot.prompt.api;

import jakarta.validation.constraints.NotBlank;

public record PrepareRequest(String userId, String sessionId, @NotBlank String query) {
}
