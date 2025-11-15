package com.example.datalake.mrpot.controller;

import com.example.datalake.mrpot.dto.KbDocumentDto;
import com.example.datalake.mrpot.request.CreateKbDocumentRequest;
import com.example.datalake.mrpot.response.KbDocumentPageResponse;
import com.example.datalake.mrpot.response.KbDocumentSearchResponse;
import com.example.datalake.mrpot.service.KbDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/kb-documents")
@RequiredArgsConstructor
@Tag(name = "Knowledge Base", description = "CRUD APIs for kb_documents dataset")
public class KbDocumentController {

  private final KbDocumentService kbDocumentService;

  @GetMapping
  @Operation(summary = "List documents", description = "Returns paginated kb_documents filtered by docType if provided.")
  public KbDocumentPageResponse list(
      @RequestParam(value = "docType", required = false) String docType,
      @PageableDefault(size = 20) Pageable pageable) {
    return KbDocumentPageResponse.fromPage(kbDocumentService.list(docType, pageable));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a document by id")
  public KbDocumentDto get(@PathVariable("id") long id) {
    return kbDocumentService.get(id);
  }

  @PostMapping
  @Operation(summary = "Create a document", description = "Builds content, metadata, and embeddings using LangChain4j before persisting.")
  public ResponseEntity<KbDocumentDto> create(@Valid @RequestBody CreateKbDocumentRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(kbDocumentService.create(request));
  }

  @GetMapping("/search")
  @Operation(
      summary = "Search documents",
      description = "Embeds the provided query (e.g. the final prompt from the processors) and returns the most similar knowledge base documents.")
  public KbDocumentSearchResponse search(
      @RequestParam("query") String query,
      @RequestParam(value = "limit", defaultValue = "5") int limit) {
    return KbDocumentSearchResponse.builder()
        .query(query)
        .limit(limit)
        .results(kbDocumentService.searchMatches(query, limit))
        .build();
  }
}
