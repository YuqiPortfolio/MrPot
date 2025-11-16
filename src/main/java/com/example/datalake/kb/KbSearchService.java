package com.example.datalake.kb;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class KbSearchService {

    private final KbDocumentRepository repository;

    public KbSearchService(KbDocumentRepository repository) {
        this.repository = repository;
    }

    public List<KbDocument> findRelevantDocuments(String query, String docType, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        String trimmedQuery = StringUtils.hasText(query) ? query.trim() : null;
        if (StringUtils.hasText(trimmedQuery)) {
            List<KbDocument> result = repository.search(trimmedQuery, sanitize(docType), limit);
            if (!result.isEmpty()) {
                return result;
            }
        }

        return repository.findRecent(sanitize(docType), limit);
    }

    private String sanitize(String docType) {
        return StringUtils.hasText(docType) ? docType.trim() : null;
    }
}
