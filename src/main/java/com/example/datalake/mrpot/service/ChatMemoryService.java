package com.example.datalake.mrpot.service;

import dev.langchain4j.memory.chat.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.store.ChatMemoryStore;
import dev.langchain4j.memory.chat.store.InMemoryChatMemoryStore;
import org.springframework.stereotype.Service;

@Service
public class ChatMemoryService {

    private static final int MAX_MESSAGES = 20;

    private final ChatMemoryStore chatMemoryStore = new InMemoryChatMemoryStore();

    public ChatMemory chatMemory(String sessionId) {
        String memoryId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return MessageWindowChatMemory.builder()
                .id(memoryId)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(MAX_MESSAGES)
                .build();
    }
}
