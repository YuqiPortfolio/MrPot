package com.example.datalake.mrpot.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Data;

@Data
public class CreateKbDocumentRequest {

  @NotBlank(message = "docType is required")
  private String docType;

  private String content;

  private Map<String, Object> metadata;

  @Valid
  private ChatQaPayload chat;

  @Data
  public static class ChatQaPayload {
    @NotBlank(message = "question is required for chat_qa documents")
    private String question;

    @NotBlank(message = "answer is required for chat_qa documents")
    private String answer;
  }
}
