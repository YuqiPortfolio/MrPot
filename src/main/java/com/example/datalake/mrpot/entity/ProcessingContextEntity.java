package com.example.datalake.mrpot.entity;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.Language;
import jakarta.persistence.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@Entity
@Table(name = "processing_contexts", schema = "public")
public class ProcessingContextEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "raw_input", nullable = false, columnDefinition = "text")
    private String rawInput;

    @Column(name = "normalized", columnDefinition = "text")
    private String normalized;

    @Column(name = "language", nullable = false)
    private String language = Language.und().getIsoCode();

    @Column(name = "intent", nullable = false)
    private String intent = Intent.UNKNOWN.name();

    @Column(name = "corrected", columnDefinition = "text")
    private String corrected;

    @Column(name = "change_ratio")
    private Double changeRatio;

    @Column(name = "index_text", columnDefinition = "text")
    private String indexText;

    @Column(name = "index_language")
    private String indexLanguage = "en";

    @Column(name = "template_code")
    private String templateCode;

    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "user_prompt", columnDefinition = "text")
    private String userPrompt;

    @Column(name = "final_prompt", columnDefinition = "text")
    private String finalPrompt;

    @Column(name = "kb_context", columnDefinition = "text")
    private String kbContext;

    @Column(name = "llm_question", columnDefinition = "text")
    private String llmQuestion;

    @Column(name = "kb_snippet_count")
    private Integer kbSnippetCount;

    @Column(name = "char_limit")
    private Integer charLimit = 8000;

    @Column(name = "cache_key")
    private String cacheKey;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "cache_frequency")
    private Integer cacheFrequency;

    @Column(name = "common_response", nullable = false)
    private boolean commonResponse;

    @Column(name = "llm_answer", columnDefinition = "text")
    private String llmAnswer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "context", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC")
    private List<ProcessingStepLogEntity> steps = new ArrayList<>();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
