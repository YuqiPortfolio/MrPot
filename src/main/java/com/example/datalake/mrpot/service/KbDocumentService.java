package com.example.datalake.mrpot.service;

import com.example.datalake.mrpot.dao.KbDocumentRepository;
import com.example.datalake.mrpot.dto.KbDocumentDto;
import com.example.datalake.mrpot.model.KbDocument;
import com.example.datalake.mrpot.model.KnowledgeBaseMatch;
import com.example.datalake.mrpot.request.CreateKbDocumentRequest;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class KbDocumentService {

  private static final Set<String> SUPPORTED_TYPES = Set.of("blog", "chat_qa");
  private static final int DEFAULT_SEARCH_LIMIT = 5;
  private static final int MAX_SEARCH_LIMIT = 50;

  private final KbDocumentRepository repository;
  private final EmbeddingModel embeddingModel;

  public Page<KbDocumentDto> list(String docType, Pageable pageable) {
    if (StringUtils.hasText(docType)) {
      return repository.findByDocTypeIgnoreCase(docType, pageable).map(this::toDto);
    }
    return repository.findAll(pageable).map(this::toDto);
  }

  public KbDocumentDto get(long id) {
    return repository.findById(id)
        .map(this::toDto)
        .orElseThrow(() -> new IllegalArgumentException("Document %d not found".formatted(id)));
  }

  public KbDocumentDto create(CreateKbDocumentRequest request) {
    String docType = normalizeType(request.getDocType());
    String content = renderContent(docType, request);
    List<Double> embedding = generateEmbedding(content);

    Map<String, Object> metadata = new HashMap<>();
    if (request.getMetadata() != null) {
      metadata.putAll(request.getMetadata());
    }
    metadata.putIfAbsent("type", docType);
    metadata.putIfAbsent("idx", 0);
    metadata.putIfAbsent("created_at", OffsetDateTime.now().toString());

    KbDocument entity = KbDocument.builder()
        .docType(docType)
        .content(content)
        .metadata(metadata)
        .embedding(embedding)
        .build();

    KbDocument saved = repository.save(entity);
    return toDto(saved);
  }

  public List<KnowledgeBaseMatch> searchMatches(String query, int limit) {
    if (!StringUtils.hasText(query)) {
      throw new IllegalArgumentException("query is required");
    }
    int resolvedLimit = limit <= 0 ? DEFAULT_SEARCH_LIMIT : Math.min(limit, MAX_SEARCH_LIMIT);
    String normalizedQuery = query.trim();
    if (normalizedQuery.isEmpty()) {
      throw new IllegalArgumentException("query is required");
    }
    List<Double> queryVector = generateEmbedding(normalizedQuery);
    List<KbDocument> documents = repository.findAll();
    if (documents.isEmpty()) {
      return List.of();
    }

    return documents.stream()
        .filter(doc -> doc.getEmbedding() != null && !doc.getEmbedding().isEmpty())
        .map(doc -> new MatchCandidate(doc, cosineSimilarity(queryVector, doc.getEmbedding())))
        .filter(candidate -> !Double.isNaN(candidate.similarity()))
        .sorted(Comparator.comparingDouble(MatchCandidate::similarity).reversed())
        .limit(resolvedLimit)
        .map(candidate -> toMatch(candidate.document(), candidate.similarity()))
        .toList();
  }

  private String normalizeType(String docType) {
    if (!StringUtils.hasText(docType)) {
      throw new IllegalArgumentException("docType is required");
    }
    String normalized = docType.trim().toLowerCase(Locale.ROOT);
    if (!SUPPORTED_TYPES.contains(normalized)) {
      throw new IllegalArgumentException("Unsupported docType: " + docType);
    }
    return normalized;
  }

  private String renderContent(String docType, CreateKbDocumentRequest request) {
    if ("chat_qa".equals(docType)) {
      CreateKbDocumentRequest.ChatQaPayload chat = request.getChat();
      if (chat == null) {
        throw new IllegalArgumentException("chat payload is required for chat_qa documents");
      }
      if (!StringUtils.hasText(chat.getQuestion()) || !StringUtils.hasText(chat.getAnswer())) {
        throw new IllegalArgumentException("Both question and answer are required for chat_qa documents");
      }
      return "【问题】\n" + chat.getQuestion().trim() + "\n\n【回答】\n" + chat.getAnswer().trim();
    }

    if (!StringUtils.hasText(request.getContent())) {
      throw new IllegalArgumentException("content is required for blog documents");
    }
    return request.getContent().trim();
  }

  private List<Double> generateEmbedding(String content) {
    Embedding embedding = embeddingModel.embed(content).content();
    float[] vector = embedding.vector();
    List<Double> result = new ArrayList<>(vector.length);
    for (float value : vector) {
      result.add((double) value);
    }
    return result;
  }

  private KbDocumentDto toDto(KbDocument entity) {
    List<Double> embedding = copyEmbedding(entity.getEmbedding());
    return KbDocumentDto.builder()
        .id(entity.getId())
        .docType(entity.getDocType())
        .content(entity.getContent())
        .metadata(copyMetadata(entity.getMetadata()))
        .embeddingDimension(embedding.size())
        .embedding(embedding)
        .build();
  }

  private Map<String, Object> copyMetadata(Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }

  private List<Double> copyEmbedding(List<Double> source) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    return List.copyOf(source);
  }

  private KnowledgeBaseMatch toMatch(KbDocument entity, double similarity) {
    List<Double> embedding = copyEmbedding(entity.getEmbedding());
    return KnowledgeBaseMatch.builder()
        .documentId(entity.getId())
        .docType(entity.getDocType())
        .content(entity.getContent())
        .metadata(copyMetadata(entity.getMetadata()))
        .similarity(similarity)
        .embeddingDimension(embedding.size())
        .build();
  }

  private double cosineSimilarity(List<Double> left, List<Double> right) {
    if (left.isEmpty() || right == null || right.isEmpty()) {
      return Double.NaN;
    }
    int dimension = Math.min(left.size(), right.size());
    if (dimension == 0) {
      return Double.NaN;
    }
    double dot = 0;
    double leftMagnitude = 0;
    double rightMagnitude = 0;
    for (int i = 0; i < dimension; i++) {
      double l = left.get(i);
      double r = right.get(i);
      dot += l * r;
      leftMagnitude += l * l;
      rightMagnitude += r * r;
    }
    if (leftMagnitude == 0 || rightMagnitude == 0) {
      return Double.NaN;
    }
    return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
  }

  private record MatchCandidate(KbDocument document, double similarity) {}
}
