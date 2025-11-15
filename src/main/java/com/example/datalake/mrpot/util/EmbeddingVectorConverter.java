package com.example.datalake.mrpot.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Converter(autoApply = false)
public class EmbeddingVectorConverter implements AttributeConverter<List<Double>, String> {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public String convertToDatabaseColumn(List<Double> attribute) {
    if (attribute == null || attribute.isEmpty()) {
      return null;
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(attribute);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Unable to serialize embedding vector", e);
    }
  }

  @Override
  public List<Double> convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isBlank()) {
      return Collections.emptyList();
    }
    try {
      return OBJECT_MAPPER.readValue(dbData, OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, Double.class));
    } catch (IOException e) {
      throw new IllegalStateException("Unable to deserialize embedding vector", e);
    }
  }
}
