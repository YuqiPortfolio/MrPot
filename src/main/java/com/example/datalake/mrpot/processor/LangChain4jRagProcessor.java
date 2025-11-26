package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.service.LangChain4jRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class LangChain4jRagProcessor implements TextProcessor {

    private final LangChain4jRagService ragService;

    @Override
    public String name() {
        return "langchain4j-rag";
    }

    @Override
    public Mono<ProcessingContext> process(ProcessingContext ctx) {
        // 如果已经走了 CommonResponse（例如“你好”“hi” 这种），就不要再调 LLM 了
        if (ctx.isCommonResponse()) {
            return Mono.just(ctx.addStep(name(), "skip-common-response"));
        }

        // cacheHit 只是在 PromptCacheLookup 那个阶段起作用，LLM 还是要跑一次的
        return ragService.prepare(ctx);
    }
}
