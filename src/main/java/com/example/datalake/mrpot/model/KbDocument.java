package com.example.datalake.mrpot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "kb_documents", schema = "public")
@Data
public class KbDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_type", nullable = false)
    private String docType;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /**
     * metadata jsonb 简单先用 String 保存原始 JSON 文本。
     * 以后你想用 JsonNode / Map 再改 Convertor 就行。
     */
    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;
}
