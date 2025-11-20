package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.model.KbSnippet;

import java.util.List;

public interface KbSearchService {

    /**
     * 基于 query + keywords 进行检索，返回「短片段」。
     *
     * @param query          英文检索文本（来自 ctx.indexText / userPrompt 的英文版本）
     * @param keywords       上游 Processor 提取的关键词
     * @param maxSnippets    最多返回多少片段
     * @param maxTotalChars  所有 snippet 的字符总预算（服务端粗控）
     */
    List<KbSnippet> searchSnippets(String query,
                                   List<String> keywords,
                                   int maxSnippets,
                                   int maxTotalChars);
}
