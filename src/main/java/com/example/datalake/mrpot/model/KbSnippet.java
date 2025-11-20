package com.example.datalake.mrpot.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KbSnippet {
    private Long docId;
    private String title;
    private String source;
    private String snippet;
    private double score;
}
