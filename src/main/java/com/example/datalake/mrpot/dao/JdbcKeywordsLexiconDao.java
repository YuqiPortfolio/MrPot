package com.example.datalake.mrpot.dao;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcKeywordsLexiconDao implements KeywordsLexiconDao {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<String, Set<String>> loadLexicon() {
        final String sql = "select canonical, synonyms from public.keywords_lexicon where is_active = true";

        try {
            return jdbcTemplate.query(sql, this::mapResultSet);
        } catch (DataAccessException e) {
            log.warn("[intent-classifier] Failed to load keywords lexicon from database – {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Set<String>> mapResultSet(ResultSet rs) throws SQLException {
        Map<String, Set<String>> lexicon = new LinkedHashMap<>();
        while (rs.next()) {
            String canonical = toLower(rs.getString("canonical"));
            if (canonical == null) {
                continue;
            }

            Set<String> synonyms = new LinkedHashSet<>();
            synonyms.add(canonical);

            Array synArray = rs.getArray("synonyms");
            if (synArray != null) {
                Object array = synArray.getArray();
                if (array instanceof String[] values) {
                    for (String value : values) {
                        addLower(synonyms, value);
                    }
                }
            }

            lexicon.put(canonical, synonyms);
        }
        log.info("[intent-classifier] Loaded {} keyword lexicon entries from database", lexicon.size());
        return lexicon;
    }

    private void addLower(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value.toLowerCase(Locale.ROOT));
        }
    }

    private String toLower(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
