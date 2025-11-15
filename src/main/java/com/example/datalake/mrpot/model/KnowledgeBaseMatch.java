package com.example.datalake.mrpot.model;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class KnowledgeBaseMatch {
  Long documentId;
  String docType;
  String content;
  Map<String, Object> metadata;
  double similarity;
  int embeddingDimension;
}
