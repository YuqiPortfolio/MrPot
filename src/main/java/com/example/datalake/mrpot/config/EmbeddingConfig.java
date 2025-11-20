package com.example.datalake.mrpot.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding model used for semantic search against kb_documents.embedding.
 */
@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel(Langchain4jOpenAiProperties props) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getEmbeddingModel())
                .build();
    }
}
