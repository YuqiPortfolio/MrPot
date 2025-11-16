package com.example.datalake.kb;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    @Query(
            value =
                    "SELECT * FROM kb_documents "
                            + "WHERE (:docType IS NULL OR doc_type = :docType) "
                            + "AND to_tsvector('simple', coalesce(content, '')) "
                            + "@@ plainto_tsquery('simple', :query) "
                            + "ORDER BY ts_rank_cd(to_tsvector('simple', coalesce(content, '')), "
                            + "plainto_tsquery('simple', :query)) DESC LIMIT :limit",
            nativeQuery = true)
    List<KbDocument> search(@Param("query") String query, @Param("docType") String docType, @Param("limit") int limit);

    @Query(
            value =
                    "SELECT * FROM kb_documents "
                            + "WHERE (:docType IS NULL OR doc_type = :docType) "
                            + "ORDER BY updated_at DESC NULLS LAST, id DESC LIMIT :limit",
            nativeQuery = true)
    List<KbDocument> findRecent(@Param("docType") String docType, @Param("limit") int limit);
}
