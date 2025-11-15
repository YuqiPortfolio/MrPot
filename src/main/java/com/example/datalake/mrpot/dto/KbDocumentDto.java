package com.example.datalake.mrpot.dto;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class KbDocumentDto {
  Long id;
  String docType;
  String content;
  Map<String, Object> metadata;
  int embeddingDimension;
  List<Double> embedding;
}
