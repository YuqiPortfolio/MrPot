package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbSnippet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

/**
 * KbSearchService implementation backed by Supabase Postgres + pgvector
 * using table public.kb_documents:
 *
 *   id        bigserial primary key,
 *   doc_type  varchar(255) not null,
 *   content   text not null,
 *   metadata  jsonb,
 *   embedding vector
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupabaseKbSearchService implements KbSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    @Override
    public List<KbSnippet> searchSnippets(String queryText,
                                          List<String> keywords,
                                          int topK) {

        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        try {
            // 1) Embed query text
            Embedding emb = embeddingModel.embed(queryText).content();
            PGvector queryVector = new PGvector(emb.vectorAsList());

            // 2) Vector search using pgvector distance operator <=>
            String sql = """
                    select
                      id,
                      doc_type,
                      content,
                      metadata,
                      1 - (embedding <=> ?) as similarity
                    from public.kb_documents
                    where embedding is not null
                    order by embedding <=> ?
                    limit ?
                    """;

            return jdbcTemplate.query(conn -> {
                        PreparedStatement ps = conn.prepareStatement(sql);
                        // For similarity expression
                        ps.setObject(1, queryVector);
                        // For ORDER BY
                        ps.setObject(2, queryVector);
                        ps.setInt(3, topK);
                        return ps;
                    },
                    (rs, rowNum) -> {
                        KbSnippet s = new KbSnippet();
                        s.setId(rs.getLong("id"));
                        s.setDocType(rs.getString("doc_type"));
                        s.setContent(rs.getString("content"));

                        String json = rs.getString("metadata");
                        if (json != null) {
                            s.setMetadata(deserializeMetadata(json));
                        }

                        s.setSimilarity(rs.getDouble("similarity"));
                        return s;
                    });

        } catch (Exception e) {
            log.error("Error searching kb_documents with queryText={}", queryText, e);
            return List.of();
        }
    }

    private Map<String, Object> deserializeMetadata(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = objectMapper.readValue(json, Map.class);
            return meta;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse kb_documents.metadata: {}", json, e);
            return null;
        }
    }
}
