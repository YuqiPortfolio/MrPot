package com.example.datalake.mrpot.config;

import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChain4jConfig {

  @Bean
  public EmbeddingModel embeddingModel() {
    return AllMiniLmL6V2EmbeddingModel.builder().build();
  }
}
