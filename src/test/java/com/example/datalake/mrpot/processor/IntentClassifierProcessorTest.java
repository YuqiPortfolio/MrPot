package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.dao.IntentRulesDao;
import com.example.datalake.mrpot.dao.KeywordsLexiconDao;
import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.ProcessingContext;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class IntentClassifierProcessorTest {

  private final KeywordsLexiconDao emptyLexiconDao = token -> Collections.emptySet();
  private final IntentRulesDao emptyRulesDao = tokens -> Collections.emptyList();

  @Test
  void vehicle_shouldBeDetected() {
    IntentClassifierProcessor p = new IntentClassifierProcessor(
            emptyLexiconDao,
            rules("vehicle", List.of("rav4", "cr-v", "cx-5", "msrp", "otd"))
    );
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("What's a realistic OTD for a 2024 RAV4 in Utah?");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.VEHICLE, out.getIntent());
    assertTrue(out.getTags().contains("intent:vehicle"));
  }

  @Test
  void travel_shouldBeDetected() {
    IntentClassifierProcessor p = new IntentClassifierProcessor(
            emptyLexiconDao,
            rules("travel", List.of("zion", "national park", "itinerary", "hike"))
    );
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("Plan a two-day Zion National Park itinerary with easy hikes.");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.TRAVEL, out.getIntent());
  }

  @Test
  void code_shouldBeDetected_langchain4j() {
    IntentClassifierProcessor p = new IntentClassifierProcessor(
            emptyLexiconDao,
            rules("code", List.of("langchain4j", "sse", "@preauthorize", "elasticsearch", "spring", "java", "react"))
    );
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("How to build SSE streaming with LangChain4j in Spring Boot?");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.CODE, out.getIntent());
  }

  @Test
  void tax_shouldBeDetected() {
    IntentClassifierProcessor p = new IntentClassifierProcessor(
            emptyLexiconDao,
            rules("tax", List.of("tax", "irs", "refund", "deduction", "filing"))
    );
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("How can I maximize my tax refund for 2025 filing?");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.TAX, out.getIntent());
  }

  @Test
  void jobs_shouldBeDetected_withBigram() {
    IntentClassifierProcessor p = new IntentClassifierProcessor(
            emptyLexiconDao,
            rules("jobs", List.of("jobs", "hiring", "openings", "software engineer", "sde"))
    );
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    // tokenizer will produce bigram "software engineer"
    ctx.setIndexText("List software engineer jobs for new grads.");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.JOBS, out.getIntent());
  }

  @Test
  void unknown_whenNoRulesLoaded() {
    IntentClassifierProcessor p = new IntentClassifierProcessor(emptyLexiconDao, emptyRulesDao);
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("Random query that should not match any intent.");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.UNKNOWN, out.getIntent());
    assertTrue(out.getTags().contains("intent:unknown"));
  }

  @Test
  void greeting_shouldBeDetectedWithoutRules() {
    IntentClassifierProcessor p = new IntentClassifierProcessor(emptyLexiconDao, emptyRulesDao);
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("Hello there!");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.GREETING, out.getIntent());
    assertTrue(out.getTags().contains("intent:greeting"));
  }

  private IntentRulesDao rules(String canonical, List<String> synonyms) {
    IntentRulesDao.IntentRuleEntry entry = new IntentRulesDao.IntentRuleEntry(
            canonical.toUpperCase(Locale.ROOT),
            synonyms
    );
    return tokens -> List.of(entry);
  }
}
