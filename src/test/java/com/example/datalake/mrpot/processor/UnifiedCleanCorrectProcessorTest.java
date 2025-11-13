// src/test/java/com/example/datalake/mrpot/processor/UnifiedCleanCorrectProcessorTest.java
package com.example.datalake.mrpot.processor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.datalake.mrpot.model.ProcessingContext;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnifiedCleanCorrectProcessorTest {
  private UnifiedCleanCorrectProcessor newProcessor() {
    return new UnifiedCleanCorrectProcessor();
  }

  private ProcessingContext mockCtx(String raw, int charLimit) {
    ProcessingContext ctx = mock(ProcessingContext.class);
    when(ctx.getRawInput()).thenReturn(raw);
    when(ctx.getCharLimit()).thenReturn(charLimit);
    when(ctx.addStep(anyString(), anyString())).thenReturn(ctx);
    return ctx;
  }

  @Test
  void basicNormalization_and_UrlsEmailsUntouched_noTypoFix() {
    String raw = "i like teh apples!! See https://example.com and email foo.bar+baz@test.co.uk";
    UnifiedCleanCorrectProcessor processor = newProcessor();
    ProcessingContext ctx = mockCtx(raw, 4096);

    processor.process(ctx).block();

    ArgumentCaptor<String> corrected = ArgumentCaptor.forClass(String.class);
    verify(ctx).setCorrected(corrected.capture());
    String out = corrected.getValue();
    assertNotNull(out);

    // Sentence-start 'i' should become 'I'
    assertTrue(out.startsWith("I "), "Sentence-start 'i' should become 'I'");

    // No typo correction now: 'teh' should remain, but '!!' -> '!'
    assertTrue(out.contains("teh apples!"),
        "'teh' should remain (no typo rules), and '!!' should normalize to '!'");

    // URL and Email must be preserved verbatim
    assertTrue(out.contains("https://example.com"), "URL must be preserved");
    assertTrue(out.contains("foo.bar+baz@test.co.uk"), "Email must be preserved");
  }

  @Test
  void codeFences_areKept_butWhitespaceMayChange_afterCondense_noTypoFix() {
    String code = "```\nfunction test(){\n  console.log('teh');\n}\n```";
    String raw = "please fix teh code:\n" + code + "\nthanks!";
    UnifiedCleanCorrectProcessor processor = newProcessor();
    ProcessingContext ctx = mockCtx(raw, 4096);

    processor.process(ctx).block();

    ArgumentCaptor<String> corrected = ArgumentCaptor.forClass(String.class);
    verify(ctx).setCorrected(corrected.capture());
    String out = corrected.getValue();
    assertNotNull(out);

    // Fence markers must exist (open/close pair)
    int open = out.indexOf("```");
    int close = out.lastIndexOf("```");
    assertTrue(open >= 0 && close > open, "Code fence markers must be present");

    // Extract inside fence content (indentation may have been trimmed)
    String inside = out.substring(open + 3, close);
    assertTrue(
        inside.contains("function test(){"), "Function header should still be inside the fence");
    assertTrue(
        inside.contains("console.log('teh');"), "Console line should still be inside the fence");
    assertTrue(inside.contains("}"), "Closing brace should still be inside the fence");

    // Text outside code fence should remain 'teh' (no typo fix), case-insensitive for 'please'
    assertTrue(java.util.regex.Pattern.compile("(?i)please\\s+fix\\s+teh\\s+code:\\s*```")
                   .matcher(out)
                   .find(),
        "Outside text should keep 'teh' and may be adjacent to the fence");

    // Sanity: code inside fence is untouched
    assertTrue(inside.contains("teh"), "Code inside fence should not be altered by corrections");
  }

  @Test
  void chaoticLogic_mixedLang_cleanup_and_classification_relaxedSpaces() {
    String raw = "it must be JSON schema; 字段: name, age; 不要包含地址!!\n"
        + "这是 测试  ，，， 好！！\n"
        + "write summary.\n"
        + "OUTPUT: return as YAML.\n";

    UnifiedCleanCorrectProcessor processor = newProcessor();
    ProcessingContext ctx = mockCtx(raw, 4096);

    processor.process(ctx).block();

    ArgumentCaptor<String> corrected = ArgumentCaptor.forClass(String.class);
    verify(ctx).setCorrected(corrected.capture());
    String out = corrected.getValue();
    assertNotNull(out);

    // Accept both CJK and ASCII comma/exclamation after NFKC; spaces around comma optional.
    assertTrue(Pattern.compile("这是测试\\s*[，,]\\s*好[！!]").matcher(out).find(),
        "Chinese spacing/punctuation should be normalized (CJK or ASCII ok; spaces around comma "
        + "optional)");
    // ASCII commas collapsed: ",,," -> ","
    assertFalse(out.contains(",,,"), "Repeated ASCII commas should be collapsed to a single ','");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, List<String>>> outlineCap = ArgumentCaptor.forClass(Map.class);
    verify(ctx).setOutline(outlineCap.capture());
    Map<String, List<String>> outline = outlineCap.getValue();
    assertNotNull(outline);

    List<String> constraints = outline.get("CONSTRAINTS");
    assertNotNull(constraints);
    assertTrue(constraints.stream().anyMatch(s -> s.toLowerCase().contains("must be json schema")),
        "Constraint with 'must' should be classified into CONSTRAINTS");
    assertTrue(constraints.stream().anyMatch(s -> s.contains("不要包含地址")),
        "Chinese '不要包含地址' should be in CONSTRAINTS");

    List<String> tasks = outline.get("TASKS");
    assertNotNull(tasks);
    assertTrue(tasks.stream().anyMatch(s -> s.toLowerCase().startsWith("write summary")),
        "'write summary.' should be classified into TASKS");

    List<String> outputs = outline.get("OUTPUT");
    assertNotNull(outputs);
    assertTrue(outputs.stream().anyMatch(s -> s.toLowerCase().startsWith("output: return as yaml")),
        "Explicit 'OUTPUT: return as YAML.' should be in OUTPUT bucket");
  }

  @Test
  void lengthControl_truncates_whenOver2000_and_appendsMarker() {
    // Build a >2000 chars input to trigger truncation at the minimum limit (2000)
    StringBuilder sb = new StringBuilder();
    sb.append("write summary.\n");
    for (int i = 0; i < 3100; i++) sb.append('x'); // total ≈ 3115 > 2000
    String raw = sb.toString();

    UnifiedCleanCorrectProcessor processor = newProcessor();
    ProcessingContext ctx = mockCtx(raw, 1000); // limit = max(2000, min(1000, 8000)) = 2000

    processor.process(ctx).block();

    ArgumentCaptor<String> corrected = ArgumentCaptor.forClass(String.class);
    verify(ctx).setCorrected(corrected.capture());
    String out = corrected.getValue();
    assertNotNull(out);

    assertTrue(out.length() <= 2000, "Output should be truncated to the enforced 2000 limit");
    assertTrue(out.contains("[Content condensed to enforce length limit]"),
        "Should append truncation marker when content is condensed");
  }

  @Test
  void dedupAcrossLines_removesCaseInsensitiveDuplicates_andFormatsToSingleLine() {
    String raw = "hello\nHELLO\nHello\n\nworld\n\n\nworld\n";

    UnifiedCleanCorrectProcessor processor = newProcessor();
    ProcessingContext ctx = mockCtx(raw, 4096);

    processor.process(ctx).block();

    ArgumentCaptor<String> corrected = ArgumentCaptor.forClass(String.class);
    verify(ctx).setCorrected(corrected.capture());
    String out = corrected.getValue();
    assertNotNull(out);

    // Should be a single line "Hello world"
    assertEquals("Hello world", out, "Should merge simple lines and capitalize the first word");

    // Sanity checks for deduplication
    assertFalse(out.contains("\n"), "No newlines expected after merging");
    assertTrue(out.startsWith("Hello"), "First word should be capitalized");
    assertTrue(out.contains("world"));
  }

  @Test
  void sentenceStart_i_isCapitalized_inMultipleSentences() {
    String raw = "wow. i am here? ok! i know.";
    UnifiedCleanCorrectProcessor processor = newProcessor();
    ProcessingContext ctx = mockCtx(raw, 4096);

    processor.process(ctx).block();

    ArgumentCaptor<String> corrected = ArgumentCaptor.forClass(String.class);
    verify(ctx).setCorrected(corrected.capture());
    String out = corrected.getValue();
    assertNotNull(out);

    // 断言两个句首的 i 都被大写
    assertTrue(out.contains(". I am here?"), "Second sentence should start with capital 'I'");
    assertTrue(out.contains("! I know."), "Fourth sentence should start with capital 'I'");
  }

  @Test
  void classification_context_fallback_when_no_keywords() {
    String raw = "This is only background information without trigger keywords.";
    UnifiedCleanCorrectProcessor processor = newProcessor();
    ProcessingContext ctx = mockCtx(raw, 4096);

    processor.process(ctx).block();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, List<String>>> outlineCap = ArgumentCaptor.forClass(Map.class);
    verify(ctx).setOutline(outlineCap.capture());
    Map<String, List<String>> outline = outlineCap.getValue();
    assertNotNull(outline);

    assertTrue(outline.get("CONTEXT").stream().anyMatch(
                   s -> s.toLowerCase().contains("background information")),
        "Sentence without task/constraint/output hints should fall into CONTEXT");
  }
}
