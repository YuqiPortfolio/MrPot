package com.example.datalake.mrpot.dao;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public interface KeywordsLexiconDao {

    /**
     * Return all canonical keywords whose canonical term or any synonym loosely matches the provided token.
     * Matching should be case-insensitive and may rely on SQL LIKE/ILIKE for light fuzziness.
     */
    Set<String> findCanonicalsByToken(String token);

    /**
     * Batch version to retrieve canonical keywords for multiple tokens in a single call.
     * Default implementation falls back to the single-token query.
     */
    default Set<String> findCanonicalsByTokens(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Set.of();
        }

        Set<String> results = new LinkedHashSet<>();
        for (String token : tokens) {
            results.addAll(findCanonicalsByToken(token));
        }
        return results;
    }
}
