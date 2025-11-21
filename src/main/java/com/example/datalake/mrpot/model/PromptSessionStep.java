package com.example.datalake.mrpot.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.Accessors;

import java.time.Instant;

@Entity
@Table(name = "prompt_session_steps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true, fluent = false)
public class PromptSessionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private PromptSession session;

    private String name;

    @Column(columnDefinition = "text")
    private String note;

    private Instant at;
}
