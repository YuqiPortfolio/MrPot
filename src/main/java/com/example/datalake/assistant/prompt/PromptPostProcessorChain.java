package com.example.datalake.assistant.prompt;

import com.example.datalake.assistant.service.PromptContext;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PromptPostProcessorChain {

    private final List<PromptPostProcessor> processors;

    public PromptPostProcessorChain(List<PromptPostProcessor> processors) {
        this.processors = processors;
    }

    public String apply(String prompt, PromptContext context) {
        String processed = prompt;
        if (processors == null) {
            return processed;
        }
        for (PromptPostProcessor processor : processors) {
            processed = processor.process(processed, context);
        }
        return processed;
    }
}
