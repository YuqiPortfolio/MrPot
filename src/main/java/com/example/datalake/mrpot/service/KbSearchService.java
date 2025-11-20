package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbSnippet;

import java.util.List;

public interface KbSearchService {

    /**
     * Semantic search in kb_documents via pgvector.
     *
     * @param queryText 用户的检索文本（已经经过前面 Processor 清洗/翻译）
     * @param keywords  关键字列表（目前实现可以先忽略，保留接口以便未来扩展）
     * @param topK      返回的最多片段数量
     */
    List<KbSnippet> searchSnippets(String queryText,
                                   List<String> keywords,
                                   int topK);
}
