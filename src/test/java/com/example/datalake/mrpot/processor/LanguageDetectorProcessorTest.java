package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Language;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.github.pemistahl.lingua.api.Language.*;
import static org.junit.jupiter.api.Assertions.*;

public class LanguageDetectorProcessorTest {
    private static LanguageDetector detector;

    @BeforeAll
    static void setUp() {
        // Build a small detector for tests (faster than all languages):
        detector = LanguageDetectorBuilder.fromLanguages(ENGLISH, CHINESE, SPANISH, JAPANESE).build();
    }

    @Test
    void chinese_to_english_indexing() {
        LanguageDetectorProcessor p = new LanguageDetectorProcessor(detector);
        ProcessingContext ctx = new ProcessingContext()
                .setRawInput("请比较RAV4 Hybrid和CR-V Hybrid十年保值与保养成本，还要考虑保险支出。");

        ProcessingContext out = p.process(ctx).block();
        assertNotNull(out);
        assertEquals("zh", out.getLanguage().getIsoCode(), "Should detect Chinese");
        assertEquals("en", out.getIndexLanguage(), "Index language is fixed to 'en'");
        assertNotNull(out.getIndexText());
        assertFalse(out.getIndexText().isBlank(), "Index text should not be blank");
        // Should be ASCII only (no Han characters)
        assertFalse(containsHan(out.getIndexText()), "Index text should contain no Han characters");
    }

    @Test
    void english_passthrough_indexing() {
        LanguageDetectorProcessor p = new LanguageDetectorProcessor(detector);
        ProcessingContext ctx = new ProcessingContext()
                .setRawInput("Please summarize the key differences between RAV4 Hybrid and CR-V Hybrid.");

        ProcessingContext out = p.process(ctx).block();
        assertNotNull(out);
        assertEquals("en", out.getLanguage().getIsoCode());
        assertEquals("en", out.getIndexLanguage());
        assertTrue(out.getIndexText().contains("please"), "Index text should be lower-cased English");
    }

    @Test
    void ignore_code_fence_when_detecting_and_indexing() {
        LanguageDetectorProcessor p = new LanguageDetectorProcessor(detector);
        String input = """
                ```java
                // English tokens should be ignored during detection/index building
                public static void main(String[] args) {}
                ```
                请帮我把上面的代码改成Kotlin版本，并解释变化点。
                """;
        ProcessingContext ctx = new ProcessingContext().setRawInput(input);

        ProcessingContext out = p.process(ctx).block();
        assertEquals("zh", out.getLanguage().getIsoCode(), "Remaining natural text is Chinese");
        assertEquals("en", out.getIndexLanguage());
        // Index text should not include code tokens like 'public'
        assertFalse(out.getIndexText().contains("public"), "Index text should exclude code fence content");
    }

    @Test
    void prefer_normalized_over_raw() {
        LanguageDetectorProcessor p = new LanguageDetectorProcessor(detector);
        ProcessingContext ctx = new ProcessingContext()
                .setRawInput("Please translate to English.")  // raw is English
                .setNormalized("请仅用中文回答。");              // normalized is Chinese → should win

        ProcessingContext out = p.process(ctx).block();
        assertEquals("zh", out.getLanguage().getIsoCode(), "Should use normalized first");
    }

    @Test
    void chinese_name_translates_to_expected_english_tokens() {
        LanguageDetectorProcessor p = new LanguageDetectorProcessor(detector);
        ProcessingContext ctx = new ProcessingContext().setRawInput("郭育奇高盛");

        ProcessingContext out = p.process(ctx).block();
        assertNotNull(out);
        assertEquals("zh", out.getLanguage().getIsoCode(), "Detection should still report Chinese");
        assertTrue(out.getIndexText().contains("yuqi guo"), "Index text should surface 'Yuqi Guo'");
        assertTrue(out.getIndexText().contains("goldman sachs"), "Index text should surface 'Goldman Sachs'");
    }

    @Test
    void empty_input_und() {
        LanguageDetectorProcessor p = new LanguageDetectorProcessor(detector);
        ProcessingContext ctx = new ProcessingContext().setRawInput("   \n\t ");

        ProcessingContext out = p.process(ctx).block();
        assertEquals("und", out.getLanguage().getIsoCode());
        assertEquals("en", out.getIndexLanguage());
        assertEquals("", out.getIndexText(), "Index text should be empty for blank input");
    }

    // helper for assertion
    private static boolean containsHan(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }
}
