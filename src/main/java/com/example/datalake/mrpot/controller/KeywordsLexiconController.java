package com.example.datalake.mrpot.controller;

import com.example.datalake.mrpot.dao.KeywordsLexiconRepository;
import com.example.datalake.mrpot.model.KeywordsLexicon;
import com.example.datalake.mrpot.request.KeywordsLexiconRequest;
import com.example.datalake.mrpot.response.KeywordsLexiconResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
@RequestMapping("/api/v1/keywords-lexicon")
@RequiredArgsConstructor
@Tag(name = "Keywords Lexicon", description = "CRUD over public.keywords_lexicon")
public class KeywordsLexiconController {

    private final KeywordsLexiconRepository repository;

    @Operation(summary = "List all keywords lexicon entries")
    @GetMapping
    public List<KeywordsLexiconResponse> findAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Operation(summary = "Get a lexicon entry by canonical term")
    @GetMapping("/{canonical}")
    public ResponseEntity<KeywordsLexiconResponse> findOne(@PathVariable String canonical) {
        return repository
                .findById(canonical)
                .map(entry -> ResponseEntity.ok(toResponse(entry)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new lexicon entry")
    @PostMapping
    public ResponseEntity<KeywordsLexiconResponse> create(
            @Valid @RequestBody KeywordsLexiconRequest request) {
        if (repository.existsById(request.canonical())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Canonical keyword already exists");
        }

        KeywordsLexicon entity = new KeywordsLexicon();
        apply(request, entity, request.canonical());
        KeywordsLexicon saved = repository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @Operation(summary = "Update an existing lexicon entry")
    @PutMapping("/{canonical}")
    public ResponseEntity<KeywordsLexiconResponse> update(
            @PathVariable String canonical, @Valid @RequestBody KeywordsLexiconRequest request) {
        if (request.canonical() != null && !Objects.equals(request.canonical(), canonical)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Canonical in path and body must match");
        }

        return repository
                .findById(canonical)
                .map(existing -> {
                    apply(request, existing, canonical);
                    return ResponseEntity.ok(toResponse(repository.save(existing)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a lexicon entry")
    @DeleteMapping("/{canonical}")
    public ResponseEntity<Void> delete(@PathVariable String canonical) {
        if (!repository.existsById(canonical)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(canonical);
        return ResponseEntity.noContent().build();
    }

    private void apply(KeywordsLexiconRequest request, KeywordsLexicon target, String canonical) {
        target.setCanonical(canonical);
        target.setSynonyms(normalizeSynonyms(request.synonyms()));
        target.setActive(request.active() == null || request.active());
    }

    private List<String> normalizeSynonyms(List<String> synonyms) {
        if (synonyms == null) {
            return Collections.emptyList();
        }
        return synonyms.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toList();
    }

    private KeywordsLexiconResponse toResponse(KeywordsLexicon entry) {
        List<String> synonyms = entry.getSynonyms() == null ? List.of() : List.copyOf(entry.getSynonyms());
        return new KeywordsLexiconResponse(
                entry.getCanonical(), synonyms, entry.isActive(), entry.getCreatedAt(), entry.getUpdatedAt());
    }
}
