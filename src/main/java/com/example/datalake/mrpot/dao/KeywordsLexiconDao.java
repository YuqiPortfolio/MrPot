package com.example.datalake.mrpot.dao;

import java.util.Set;

public interface KeywordsLexiconDao {

    /**
     * Return all canonical keywords whose canonical term or any synonym loosely matches the provided token.
     * Matching should be case-insensitive and may rely on SQL LIKE/ILIKE for light fuzziness.
     */
    Set<String> findCanonicalsByToken(String token);
}
