package com.example.datalake.mrpot.prompt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "prompt")
@Validated
public class PromptProperties {

    private String systemPrompt = "You are MrPot, a helpful data-lake assistant. Keep answers concise.";
    private String language = "en";
    private String intent = "demo.prepare";
    private final List<String> tags = new ArrayList<>(List.of("demo", "hardcoded", "sse", "prepare"));
    private final Stream stream = new Stream();

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags.clear();
        if (tags != null) {
            this.tags.addAll(tags);
        }
    }

    public Stream getStream() {
        return stream;
    }

    public static final class Stream {
        private Duration delay = Duration.ofSeconds(1);
        private final List<String> steps = new ArrayList<>(List.of(
                "parse-query",
                "detect-intent",
                "extract-entities",
                "plan-execution",
                "finalize"
        ));

        public Duration getDelay() {
            return delay;
        }

        public void setDelay(Duration delay) {
            this.delay = delay;
        }

        public List<String> getSteps() {
            return steps;
        }

        public void setSteps(List<String> steps) {
            this.steps.clear();
            if (steps != null) {
                this.steps.addAll(steps);
            }
        }
    }
}
