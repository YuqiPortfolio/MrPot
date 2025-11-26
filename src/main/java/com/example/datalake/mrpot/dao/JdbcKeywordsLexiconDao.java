package com.example.datalake.mrpot.dao;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcKeywordsLexiconDao implements KeywordsLexiconDao {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Set<String> findCanonicalsByToken(String token) {
        if (token == null || token.isBlank()) {
            return Set.of();
        }

        final String pattern = "%" + token + "%";
        final String sql = """
                select canonical
                from public.keywords_lexicon
                where is_active = true
                  and (
                    canonical ilike ?
                    or exists(
                        select 1 from unnest(synonyms) s where s ilike ?
                    )
                  )
                """;

        try {
            return new LinkedHashSet<>(jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("canonical"), pattern, pattern)
                    .stream()
                    .filter(v -> v != null && !v.isBlank())
                    .map(v -> v.toLowerCase(Locale.ROOT))
                    .toList());
        } catch (DataAccessException e) {
            log.warn("[intent-classifier] Failed to query keywords lexicon – {}", e.getMessage());
            return Set.of();
        }
    }
}
