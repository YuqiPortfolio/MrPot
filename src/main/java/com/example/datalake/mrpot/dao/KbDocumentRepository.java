package com.example.datalake.mrpot.dao;

import com.example.datalake.mrpot.model.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;
import java.util.List;

@Repository
public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    /**
     * 简单全文检索：按 content 做 ILIKE，返回最近的 top N。
     * 这里先写死 top5，后续你要加 docType、keywords 等再拓展。
     */
    List<KbDocument> findTop5ByContentContainingIgnoreCaseOrderByIdDesc(String content);
    /**
     * 用原始 query 对 content 做兜底模糊搜索
     */
    @Query("""
        SELECT d
        FROM KbDocument d
        WHERE LOWER(d.content) LIKE LOWER(CONCAT('%', :q, '%'))
        """)
    List<KbDocument> searchByFreeText(
            @Param("q") String query,
            Pageable pageable
    );
}
