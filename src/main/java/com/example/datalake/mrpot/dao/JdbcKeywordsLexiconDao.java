package com.example.datalake.mrpot.dao;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.Collection;
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

        return findCanonicalsByTokens(Set.of(token));
    }

    @Override
    public Set<String> findCanonicalsByTokens(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Set.of();
        }

        Set<String> patterns = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token != null && !token.isBlank()) {
                patterns.add("%" + token + "%");
            }
        }

        if (patterns.isEmpty()) {
            return Set.of();
        }

        final String sql = """
                select canonical
                from public.keywords_lexicon
                where is_active = true
                  and (
                    canonical ilike any (?)
                    or exists(
                        select 1 from unnest(synonyms) s where s ilike any (?)
                    )
                  )
                """;

        try {
            return new LinkedHashSet<>(jdbcTemplate.query(con -> {
                        Array patternArray = con.createArrayOf("text", patterns.toArray());
                        var ps = con.prepareStatement(sql);
                        ps.setArray(1, patternArray);
                        ps.setArray(2, patternArray);
                        return ps;
                    },
                    (rs, rowNum) -> rs.getString("canonical"))
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
