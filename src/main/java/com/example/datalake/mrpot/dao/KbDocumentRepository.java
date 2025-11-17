package com.example.datalake.mrpot.dao;

import com.example.datalake.mrpot.model.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    /**
     * 简单全文检索：按 content 做 ILIKE，返回最近的 top N。
     * 这里先写死 top5，后续你要加 docType、keywords 等再拓展。
     */
    List<KbDocument> findTop5ByContentContainingIgnoreCaseOrderByIdDesc(String content);
}
