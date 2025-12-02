package com.example.datalake.mrpot.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
@Entity
@Table(name = "processing_step_logs", schema = "public")
public class ProcessingStepLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "context_id", nullable = false)
    private ProcessingContextEntity context;

    @Column(name = "step_order")
    private Integer stepOrder;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "extra", columnDefinition = "jsonb")
    private String extra;
}
