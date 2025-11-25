package com.example.datalake.mrpot.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationMemoryService {

    private static final int MAX_MESSAGES = 20;

    private final Map<String, Deque<ChatMessage>> memories = new ConcurrentHashMap<>();

    public List<ChatMessage> getMessages(String sessionId) {
        Deque<ChatMessage> deque = memories.get(sessionId);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        return List.copyOf(deque);
    }

    public void appendTurn(String sessionId, String userText, String aiText) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Deque<ChatMessage> deque = memories.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        addWithLimit(deque, UserMessage.from(userText));
        addWithLimit(deque, AiMessage.from(aiText));
    }

    private void addWithLimit(Deque<ChatMessage> deque, ChatMessage message) {
        if (deque.size() >= MAX_MESSAGES) {
            deque.removeFirst();
        }
        deque.addLast(message);
    }
}

