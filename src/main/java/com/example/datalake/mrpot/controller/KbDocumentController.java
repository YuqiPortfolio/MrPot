package com.example.datalake.mrpot.controller;

import com.example.datalake.mrpot.dao.KbDocumentRepository;
import com.example.datalake.mrpot.model.KbDocument;
import com.example.datalake.mrpot.request.KbDocumentRequest;
import com.example.datalake.mrpot.response.KbDocumentResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/kb-documents")
@RequiredArgsConstructor
@Tag(name = "Knowledge Base Documents", description = "CRUD over public.kb_documents")
public class KbDocumentController {

    private final KbDocumentRepository repository;
    private final ObjectMapper objectMapper;

    @Operation(summary = "List all knowledge base documents")
    @GetMapping
    public List<KbDocumentResponse> findAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Operation(summary = "Get a knowledge base document by id")
    @GetMapping("/{id}")
    public ResponseEntity<KbDocumentResponse> findOne(@PathVariable Long id) {
        return repository
                .findById(id)
                .map(doc -> ResponseEntity.ok(toResponse(doc)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a knowledge base document")
    @PostMapping
    public ResponseEntity<KbDocumentResponse> create(@Valid @RequestBody KbDocumentRequest request) {
        KbDocument entity = new KbDocument();
        apply(request, entity);
        KbDocument saved = repository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @Operation(summary = "Update a knowledge base document")
    @PutMapping("/{id}")
    public ResponseEntity<KbDocumentResponse> update(
            @PathVariable Long id, @Valid @RequestBody KbDocumentRequest request) {
        return repository
                .findById(id)
                .map(existing -> {
                    apply(request, existing);
                    return ResponseEntity.ok(toResponse(repository.save(existing)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a knowledge base document")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void apply(KbDocumentRequest request, KbDocument target) {
        target.setDocType(request.docType());
        target.setContent(request.content());
        target.setMetadata(writeMetadata(request.metadata()));
    }

    private KbDocumentResponse toResponse(KbDocument doc) {
        return new KbDocumentResponse(doc.getId(), doc.getDocType(), doc.getContent(), parseMetadata(doc));
    }

    private String writeMetadata(JsonNode metadata) {
        if (metadata == null || metadata.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid metadata payload", e);
        }
    }

    private JsonNode parseMetadata(KbDocument doc) {
        String raw = doc.getMetadata();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(raw);
        }
    }
}
