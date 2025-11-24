package com.example.datalake.mrpot;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class MrPotApplicationTests {

    @MockBean
    EmbeddingStore<TextSegment> embeddingStore;
  @Test
  void contextLoads() {
  }

}
