package com.example.datalake.mrpot.dao;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcIntentRulesDao implements IntentRulesDao {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<IntentRuleEntry> findActiveRulesByTokens(Iterable<String> tokens) {
        Set<String> uniqueTokens = new HashSet<>();
        tokens.forEach(t -> {
            if (t != null && !t.isBlank()) {
                uniqueTokens.add(t);
            }
        });

        if (uniqueTokens.isEmpty()) {
            return Collections.emptyList();
        }

        final String sql = """
                select canonical, synonyms
                from public.keywords_lexicon
                where is_active = true
                  and (
                    canonical ilike ?
                    or exists(
                        select 1 from unnest(synonyms) s where s ilike ?
                    )
                  )
                """;

        Map<String, IntentRuleEntry> entries = new LinkedHashMap<>();
        for (String token : uniqueTokens) {
            String pattern = "%" + token + "%";
            try {
                jdbcTemplate.query(sql, rs -> {
                    String canonical = rs.getString("canonical");
                    if (canonical == null || canonical.isBlank()) {
                        return;
                    }

                    List<String> synonyms = new ArrayList<>();
                    Array array = rs.getArray("synonyms");
                    if (array != null) {
                        Object raw = array.getArray();
                        if (raw instanceof String[] values) {
                            for (String value : values) {
                                if (value != null && !value.isBlank()) {
                                    synonyms.add(value.toLowerCase(Locale.ROOT));
                                }
                            }
                        }
                    }

                    entries.putIfAbsent(canonical.toUpperCase(Locale.ROOT),
                            new IntentRuleEntry(canonical.toUpperCase(Locale.ROOT), synonyms));
                }, pattern, pattern);
            } catch (DataAccessException e) {
                log.warn("[intent-classifier] Failed to query intent rules – {}", e.getMessage());
            }
        }

        return new ArrayList<>(entries.values());
    }
}
