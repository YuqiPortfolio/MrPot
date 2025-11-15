package com.example.datalake.mrpot.dao;

import com.example.datalake.mrpot.model.KbDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

  Page<KbDocument> findByDocTypeIgnoreCase(String docType, Pageable pageable);
}
