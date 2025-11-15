package com.example.datalake.mrpot.response;

import com.example.datalake.mrpot.model.KnowledgeBaseMatch;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class KbDocumentSearchResponse {
  String query;
  int limit;
  List<KnowledgeBaseMatch> results;
}
