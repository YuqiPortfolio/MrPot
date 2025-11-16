package dev.langchain4j.model.embedding;

/**
 * Compatibility facade exposing the on-device AllMiniLmL6V2 embedding model
 * from {@code dev.langchain4j.model.embedding.onnx} under the legacy package
 * name expected by the rest of the application. This keeps the Spring config
 * unchanged even though the underlying implementation lives in the ONNX
 * module.
 */
public final class AllMiniLmL6V2EmbeddingModel {

  private AllMiniLmL6V2EmbeddingModel() {}

  public static dev.langchain4j.model.embedding.onnx.AllMiniLmL6V2EmbeddingModel.Builder builder() {
    return dev.langchain4j.model.embedding.onnx.AllMiniLmL6V2EmbeddingModel.builder();
  }
}
