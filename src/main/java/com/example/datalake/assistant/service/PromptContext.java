package com.example.datalake.assistant.service;

import com.example.datalake.kb.KbDocument;
import java.util.List;

public record PromptContext(String language, String intent, List<KbDocument> contextDocuments) {}
