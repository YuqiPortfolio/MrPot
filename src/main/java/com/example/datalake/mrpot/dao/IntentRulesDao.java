package com.example.datalake.mrpot.dao;

import java.util.List;

public interface IntentRulesDao {

    /**
     * Query active intent rules whose canonical term or synonyms loosely match any of the provided tokens.
     */
    List<IntentRuleEntry> findActiveRulesByTokens(Iterable<String> tokens);

    record IntentRuleEntry(String canonical, List<String> synonyms) {}
}
