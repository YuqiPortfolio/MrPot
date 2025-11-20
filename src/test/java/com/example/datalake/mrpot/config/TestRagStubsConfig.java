package com.example.datalake.mrpot.config;

import com.example.datalake.mrpot.service.KbSearchService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.embedding.EmbeddingType;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.Collections;
import java.util.List;

@TestConfiguration
@Profile("test")
public class TestRagStubsConfig {

    @Bean
    @Primary
    public ChatModel stubChatModel() {
        return message -> "[test-chat] " + message;
    }

    @Bean
    @Primary
    public EmbeddingModel stubEmbeddingModel() {
        return text -> Response.from(new Embedding(new double[1], EmbeddingType.UNKNOWN));
    }

    @Bean
    @Primary
    public KbSearchService stubKbSearchService() {
        return (queryText, keywords, topK) -> Collections.emptyList();
    }

    @Bean
    @Primary
    public EmbeddingStore<TextSegment> stubEmbeddingStore() {
        return new EmbeddingStore<>() {
            @Override
            public String add(Embedding embedding, TextSegment textSegment) { return "stub"; }
            @Override
            public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) { return List.of(); }
            @Override
            public void removeAll(List<String> ids) { }
            @Override
            public List<Embedding> getAll(List<String> ids) { return List.of(); }
            @Override
            public List<TextSegment> getRelevant(Embedding embedding, int maxResults, double minScore) { return List.of(); }
        };
    }
}
