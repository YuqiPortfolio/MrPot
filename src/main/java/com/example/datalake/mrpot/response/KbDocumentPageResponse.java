package com.example.datalake.mrpot.response;

import com.example.datalake.mrpot.dto.KbDocumentDto;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.domain.Page;

@Value
@Builder
public class KbDocumentPageResponse {

  List<KbDocumentDto> items;
  long totalElements;
  int totalPages;
  int page;
  int size;

  public static KbDocumentPageResponse fromPage(Page<KbDocumentDto> page) {
    return KbDocumentPageResponse.builder()
        .items(page.getContent())
        .totalElements(page.getTotalElements())
        .totalPages(page.getTotalPages())
        .page(page.getNumber())
        .size(page.getSize())
        .build();
  }
}
