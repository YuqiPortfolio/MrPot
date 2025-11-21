package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.dao.KeywordsLexiconDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class IntentClassifierProcessorTest {

  private final ResourceLoader loader = new DefaultResourceLoader();
  private final KeywordsLexiconDao emptyLexiconDao = Collections::emptyMap;

  @AfterEach
  void clearProps() {
    System.clearProperty("intent.rules");
    System.clearProperty("keywords.map.path");
  }

  @Test
  void vehicle_shouldBeDetected(@TempDir Path tmp) throws IOException {
    Path rules = tmp.resolve("intent_rules.json");
    String json = """
            {
              "rules": [
                { "name":"vehicle", "intent":"VEHICLE", "minScore":1,
                  "any": ["rav4","cr-v","cx-5","msrp","otd"], "all": [], "none": [], "tagsBoost": [] }
              ]
            }
            """;
    Files.writeString(rules, json, StandardCharsets.UTF_8);
    System.setProperty("intent.rules", rules.toString());

    IntentClassifierProcessor p = new IntentClassifierProcessor(loader, emptyLexiconDao);
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("What's a realistic OTD for a 2024 RAV4 in Utah?");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.VEHICLE, out.getIntent());
    assertTrue(out.getTags().contains("intent:vehicle"));
  }

  @Test
  void travel_shouldBeDetected(@TempDir Path tmp) throws IOException {
    Path rules = tmp.resolve("intent_rules.json");
    String json = """
            {
              "rules": [
                { "name":"travel", "intent":"TRAVEL", "minScore":1,
                  "any": ["zion","national park","itinerary","hike"], "all": [], "none": [], "tagsBoost": [] }
              ]
            }
            """;
    Files.writeString(rules, json, StandardCharsets.UTF_8);
    System.setProperty("intent.rules", rules.toString());

    IntentClassifierProcessor p = new IntentClassifierProcessor(loader, emptyLexiconDao);
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("Plan a two-day Zion National Park itinerary with easy hikes.");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.TRAVEL, out.getIntent());
  }

  @Test
  void code_shouldBeDetected_langchain4j(@TempDir Path tmp) throws IOException {
    Path rules = tmp.resolve("intent_rules.json");
    String json = """
            {
              "rules": [
                { "name":"code", "intent":"CODE", "minScore":1,
                  "any": ["langchain4j","sse","@preauthorize","elasticsearch","spring","java","react"],
                  "all": [], "none": [], "tagsBoost": [] }
              ]
            }
            """;
    Files.writeString(rules, json, StandardCharsets.UTF_8);
    System.setProperty("intent.rules", rules.toString());

    IntentClassifierProcessor p = new IntentClassifierProcessor(loader, emptyLexiconDao);
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("How to build SSE streaming with LangChain4j in Spring Boot?");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.CODE, out.getIntent());
  }

  @Test
  void tax_shouldBeDetected(@TempDir Path tmp) throws IOException {
    Path rules = tmp.resolve("intent_rules.json");
    String json = """
            {
              "rules": [
                { "name":"tax", "intent":"TAX", "minScore":1,
                  "any": ["tax","irs","refund","deduction","filing"], "all": [], "none": [], "tagsBoost": [] }
              ]
            }
            """;
    Files.writeString(rules, json, StandardCharsets.UTF_8);
    System.setProperty("intent.rules", rules.toString());

    IntentClassifierProcessor p = new IntentClassifierProcessor(loader, emptyLexiconDao);
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("How can I maximize my tax refund for 2025 filing?");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.TAX, out.getIntent());
  }

  @Test
  void jobs_shouldBeDetected_withBigram(@TempDir Path tmp) throws IOException {
    Path rules = tmp.resolve("intent_rules.json");
    String json = """
            {
              "rules": [
                { "name":"jobs", "intent":"JOBS", "minScore":1,
                  "any": ["jobs","hiring","openings","software engineer","sde"], "all": [], "none": [], "tagsBoost": [] }
              ]
            }
            """;
    Files.writeString(rules, json, StandardCharsets.UTF_8);
    System.setProperty("intent.rules", rules.toString());

    IntentClassifierProcessor p = new IntentClassifierProcessor(loader, emptyLexiconDao);
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    // tokenizer will produce bigram "software engineer"
    ctx.setIndexText("List software engineer jobs for new grads.");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.JOBS, out.getIntent());
  }

  @Test
  void unknown_whenNoRulesLoaded(@TempDir Path tmp) throws IOException {
    // Make this deterministic by pointing to an empty rules file
    Path rules = tmp.resolve("intent_rules.json");
    Files.writeString(rules, "{\"rules\":[]}", StandardCharsets.UTF_8);
    System.setProperty("intent.rules", rules.toString());

    IntentClassifierProcessor p = new IntentClassifierProcessor(loader, emptyLexiconDao);
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
    IntentClassifierProcessor p = new IntentClassifierProcessor(loader, emptyLexiconDao);
    ProcessingContext ctx = new ProcessingContext();
    ctx.setIndexLanguage("en");
    ctx.setIndexText("Hello there!");

    ProcessingContext out = p.process(ctx).block();
    assertNotNull(out);
    assertEquals(Intent.GREETING, out.getIntent());
    assertTrue(out.getTags().contains("intent:greeting"));
  }
}
