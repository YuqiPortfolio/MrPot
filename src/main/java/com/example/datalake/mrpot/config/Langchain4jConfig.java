package com.example.datalake.mrpot.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
@EnableConfigurationProperties({
        Langchain4jOpenAiProperties.class,
        SupabaseProps.class
})
public class Langchain4jConfig {

    @Bean
    public ChatModel chatModel(Langchain4jOpenAiProperties props) {
        return OpenAiChatModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getChatModel())
                .temperature(props.getTemperature())
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(SupabaseProps p) {
        // 注意：这里不再调用 .schema(...)
        return PgVectorEmbeddingStore.builder()
                .host(p.getHost())
                .port(p.getPort())
                .database(p.getDatabase())
                .user(p.getUser())
                .password(p.getPassword())
                // 直接让 DB 使用默认 schema=public，表名用 kb_documents
                .table(p.getTable())          // "kb_documents"
                .dimension(p.getEmbeddingDimension())
                .createTable(false)
                .dropTableFirst(false)
                .build();
    }

    @Bean
    public ContentRetriever contentRetriever(
            EmbeddingStore<TextSegment> store,
            EmbeddingModel embeddingModel
    ) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.5)
                .build();
    }
}
