package com.example.datalake.mrpot.config;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChain4jConfig {

  @Bean
  StreamingChatLanguageModel openAiStreamingChatModel(
      @Value("${langchain4j.open-ai.api-key}") String apiKey,
      @Value("${langchain4j.open-ai.chat-model.name}") String modelName,
      @Value("${langchain4j.open-ai.chat-model.temperature}") double temperature,
      @Value("${langchain4j.open-ai.max-output-tokens:256}") Integer maxOutputTokens) {
    return OpenAiStreamingChatModel.builder()
        .apiKey(apiKey)
        .modelName(modelName)
        .temperature(temperature)
        .maxOutputTokens(maxOutputTokens)
        .build();
  }
}
