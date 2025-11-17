package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.dao.KbDocumentRepository;
import com.example.datalake.mrpot.model.KbDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class KbSearchService {

    private final KbDocumentRepository repository;

    private static final int MAX_RESULTS = 5;

    public List<KbDocument> searchByUserText(String text) {
        return search(text, Collections.emptyList());
    }

    public List<KbDocument> search(String text, List<String> keywords) {
        LinkedHashMap<Long, KbDocument> merged = new LinkedHashMap<>();

        for (String kw : normalizeKeywords(keywords)) {
            List<KbDocument> docs = repository.findTop5ByContentContainingIgnoreCaseOrderByIdDesc(kw);
            merge(merged, docs);
            if (merged.size() >= MAX_RESULTS) break;
        }

        if (merged.isEmpty() && text != null && !text.isBlank()) {
            merge(merged, repository.findTop5ByContentContainingIgnoreCaseOrderByIdDesc(text));
        }

        return merged.values().stream()
                .limit(MAX_RESULTS)
                .toList();
    }

    private List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(keywords.size());
        for (String kw : keywords) {
            if (kw == null) continue;
            String t = kw.trim();
            if (!t.isBlank()) {
                cleaned.add(t);
            }
        }
        return cleaned;
    }

    private void merge(Map<Long, KbDocument> acc, List<KbDocument> docs) {
        if (docs == null) return;
        for (KbDocument doc : docs) {
            if (doc != null && doc.getId() != null) {
                acc.putIfAbsent(doc.getId(), doc);
            }
        }
    }
}
