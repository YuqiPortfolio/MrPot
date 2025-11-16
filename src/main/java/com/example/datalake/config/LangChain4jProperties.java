package com.example.datalake.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "langchain4j.open-ai")
public class LangChain4jProperties {

    /**
     * API key for OpenAI compatible endpoint. Leave empty to disable the LangChain4j pipeline.
     */
    private String apiKey;

    /**
     * Base URL of the OpenAI compatible endpoint. Optional – defaults to OpenAI.
     */
    private String baseUrl;

    /**
     * Chat model name, e.g. gpt-4o-mini or gpt-4.1.
     */
    private String chatModelName;

    /**
     * Temperature applied to the chat model.
     */
    private Double temperature = 0.2;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getChatModelName() {
        return chatModelName;
    }

    public void setChatModelName(String chatModelName) {
        this.chatModelName = chatModelName;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }
}
