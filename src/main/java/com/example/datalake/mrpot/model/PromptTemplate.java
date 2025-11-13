package com.example.datalake.mrpot.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class PromptTemplate {
  private String id;
  private String language;       // "en","zh",...
  private String intent;         // Intent name
  private String system;         // system prompt
  private String userTemplate;   // user message template
  private Map<String, String> vars; // optional
  private List<String> fewShot;     // optional
}
