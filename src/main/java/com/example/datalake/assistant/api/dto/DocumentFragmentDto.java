package com.example.datalake.assistant.api.dto;

import java.util.Map;

public record DocumentFragmentDto(Long id, String docType, String snippet, Map<String, Object> metadata) {}
