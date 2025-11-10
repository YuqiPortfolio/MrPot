// src/test/java/com/example/datalake/mrpot/processor/UnifiedCleanCorrectProcessorTest.java
package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.ProcessingContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    void typoFixes_and_UrlsEmailsUntouched() {
        String raw = "i like teh apples!! See https://example.com and email foo.bar+baz@test.co.uk";
        UnifiedCleanCorrectProcessor processor = newProcessor();
        ProcessingContext ctx = mockCtx(raw, 4096);

        processor.process(ctx).block();

        ArgumentCaptor<String> corrected = ArgumentCaptor.forClass(String.class);
        verify(ctx).setCorrected(corrected.capture());
        String out = corrected.getValue();
        assertNotNull(out);

        assertTrue(out.startsWith("I "), "Sentence-start 'i' should become 'I'");
        assertFalse(out.contains("teh"), "Misspelling 'teh' should be corrected");
        assertTrue(out.contains("the apples!"), "Double '!!' should be normalized to '!'");

        assertTrue(out.contains("https://example.com"), "URL must be preserved");
        assertTrue(out.contains("foo.bar+baz@test.co.uk"), "Email must be preserved");
    }

    @Test
    void codeFences_areKept_butWhitespaceMayChange_afterCondense() {
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
        assertTrue(inside.contains("function test(){"), "Function header should still be inside the fence");
        assertTrue(inside.contains("console.log('teh');"), "Console line should still be inside the fence");
        assertTrue(inside.contains("}"), "Closing brace should still be inside the fence");

        // Assert the outside text is corrected:
        // - case-insensitive: "please" may be capitalized to "Please"
        // - allow optional whitespace/newline between ':' and the opening fence
        assertTrue(
                java.util.regex.Pattern
                        .compile("(?i)please\\s+fix\\s+the\\s+code:\\s*```")
                        .matcher(out)
                        .find(),
                "Text outside code fence should be corrected (case-insensitive) and may be adjacent to the fence"
        );

        // Sanity: code inside fence should not be corrected ('teh' remains)
        assertTrue(inside.contains("teh"), "Code inside fence should not be altered by corrections");
    }

    @Test
    void chaoticLogic_mixedLang_cleanup_and_classification_relaxedSpaces() {
        String raw =
                "it must be JSON schema; 字段: name, age; 不要包含地址!!\n" +
                        "这是 测试  ，，， 好！！\n" +
                        "write summary.\n" +
                        "OUTPUT: return as YAML.\n";

        UnifiedCleanCorrectProcessor processor = newProcessor();
        ProcessingContext ctx = mockCtx(raw, 4096);

        processor.process(ctx).block();

        ArgumentCaptor<String> corrected = ArgumentCaptor.forClass(String.class);
        verify(ctx).setCorrected(corrected.capture());
        String out = corrected.getValue();
        assertNotNull(out);

        // Accept both CJK and ASCII comma/exclamation after NFKC; spaces around comma optional.
        assertTrue(
                Pattern.compile("这是测试\\s*[，,]\\s*好[！!]").matcher(out).find(),
                "Chinese spacing/punctuation should be normalized (CJK or ASCII ok; spaces around comma optional)"
        );
        // And ensure repeated ASCII commas are collapsed: ",,," -> ","
        // 同时校验 ASCII 逗号被压缩
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
        // 中文：构造超过 2000 的内容，并将 charLimit 设低，强制 limit = 2000
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

        assertTrue(outline.get("CONTEXT").stream()
                        .anyMatch(s -> s.toLowerCase().contains("background information")),
                "Sentence without task/constraint/output hints should fall into CONTEXT");
    }
}
