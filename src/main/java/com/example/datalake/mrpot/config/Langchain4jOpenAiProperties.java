package com.example.datalake.mrpot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds properties:
 *
 * langchain4j.openai.api-key=...
 * langchain4j.openai.chat-model=...
 * langchain4j.openai.embedding-model=...
 * langchain4j.openai.temperature=0.2
 */
@Data
@ConfigurationProperties(prefix = "langchain4j.openai")
public class Langchain4jOpenAiProperties {

    /**
     * OpenAI API key
     */
    private String apiKey;

    /**
     * Chat model name, e.g. "gpt-4o-mini"
     */
    private String chatModel;

    /**
     * Embedding model name, e.g. "text-embedding-3-small"
     */
    private String embeddingModel;

    /**
     * Chat model temperature
     */
    private double temperature = 0.2;
}
