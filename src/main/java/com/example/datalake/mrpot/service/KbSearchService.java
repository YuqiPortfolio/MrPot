package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.dao.KbDocumentRepository;
import com.example.datalake.mrpot.model.KbDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KbSearchService {

    private final KbDocumentRepository repository;

    public List<KbDocument> searchByUserText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        // 简单版本：直接用整个 userText 做模糊匹配
        return repository.findTop5ByContentContainingIgnoreCaseOrderByIdDesc(text);
    }
}
