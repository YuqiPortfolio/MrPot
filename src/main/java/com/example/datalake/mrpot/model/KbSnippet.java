package com.example.datalake.mrpot.model;

import lombok.Data;

import java.util.Map;

/**
 * Small piece of knowledge retrieved from kb_documents for RAG.
 */
@Data
public class KbSnippet {

    private long id;

    /**
     * doc_type in kb_documents, e.g. "blog", "project", "life" ...
     */
    private String docType;

    /**
     * The actual text chunk used as context for RAG.
     */
    private String content;

    /**
     * Optional metadata JSON from kb_documents.metadata.
     */
    private Map<String, Object> metadata;

    /**
     * Similarity score in [0,1], higher is more similar.
     */
    private double similarity;
}
