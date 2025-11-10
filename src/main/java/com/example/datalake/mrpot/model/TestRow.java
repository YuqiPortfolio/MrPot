package com.example.datalake.mrpot.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "\"Test\"", schema = "public") // quoted because of caps + reserved-ish name
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TestRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "\"Name\"") // quoted column
    private String name;
}
