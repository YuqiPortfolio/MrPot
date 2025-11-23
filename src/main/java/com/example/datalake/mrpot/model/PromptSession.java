package com.example.datalake.mrpot.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "prompt_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true, fluent = false)
public class PromptSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;

    /**
     * Client-provided or generated session id to correlate runs.
     */
    @Column(nullable = false)
    private String sessionId;

    @Column(columnDefinition = "text")
    private String rawInput;

    @Column(columnDefinition = "text")
    private String normalized;

    private String language;

    private String intent;

    @Column(columnDefinition = "text")
    private String systemPrompt;

    @Column(columnDefinition = "text")
    private String userPrompt;

    @Column(columnDefinition = "text")
    private String finalPrompt;

    @Column(columnDefinition = "text")
    private String answer;

    private boolean cacheHit;

    private String cacheKey;

    private Instant createdAt;

    @ElementCollection
    @CollectionTable(name = "prompt_session_tags", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "tag")
    @Builder.Default
    private Set<String> tags = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "prompt_session_notices", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "notice", columnDefinition = "text")
    @Builder.Default
    private List<String> validationNotices = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PromptSessionStep> steps = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PromptSessionDocument> documents = new ArrayList<>();
}
