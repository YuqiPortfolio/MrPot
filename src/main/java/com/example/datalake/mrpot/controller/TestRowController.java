package com.example.datalake.mrpot.controller;
import com.example.datalake.mrpot.dao.TestRowRepository;
import com.example.datalake.mrpot.dto.TestRowDto;
import com.example.datalake.mrpot.model.TestRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tests")
@Tag(name = "Test Table", description = "CRUD over public.\"Test\"")
public class TestRowController {
  @Autowired private TestRowRepository repository;

  @Operation(summary = "Get all rows in public.\"Test\"")
  @GetMapping
  public List<TestRowDto> findAll() {
    return repository.findAll().stream().map(e -> new TestRowDto(e.getId(), e.getName())).toList();
  }
}
