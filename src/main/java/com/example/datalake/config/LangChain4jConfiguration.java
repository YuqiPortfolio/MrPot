package com.example.datalake.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LangChain4jProperties.class)
public class LangChain4jConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "langchain4j.open-ai", name = "api-key")
    public ChatLanguageModel chatLanguageModel(LangChain4jProperties properties) {
        OpenAiChatModel.OpenAiChatModelBuilder builder =
                OpenAiChatModel.builder()
                        .apiKey(properties.getApiKey())
                        .timeout(Duration.ofSeconds(60))
                        .temperature(properties.getTemperature());

        if (properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) {
            builder.baseUrl(properties.getBaseUrl());
        }
        if (properties.getChatModelName() != null && !properties.getChatModelName().isBlank()) {
            builder.modelName(properties.getChatModelName());
        }

        return builder.build();
    }
}
