package com.example.datalake.mrpot.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

  private static final int DEFAULT_MEMORY_WINDOW = 30;

  private final StreamingChatLanguageModel streamingChatLanguageModel;
  private final Map<String, ChatMemory> memoryBySession = new ConcurrentHashMap<>();

  public ChatService(StreamingChatLanguageModel streamingChatLanguageModel) {
    this.streamingChatLanguageModel = streamingChatLanguageModel;
  }

  public Flux<ServerSentEvent<String>> streamChat(ChatRequest request) {
    ChatMemory memory = memoryBySession.computeIfAbsent(
        request.sessionId(), id -> MessageWindowChatMemory.withMaxMessages(DEFAULT_MEMORY_WINDOW));

    UserMessage userMessage = UserMessage.from(request.message());
    memory.add(userMessage);

    List<ChatMessage> chatMessages = new ArrayList<>(memory.messages());

    return Flux.create(
        sink ->
            streamingChatLanguageModel.generate(
                chatMessages,
                new StreamingResponseHandler<AiMessage>() {
                  private final StringBuilder assistantContent = new StringBuilder();

                  @Override
                  public void onNext(String token) {
                    assistantContent.append(token);
                    sink.next(ServerSentEvent.builder(token).build());
                  }

                  @Override
                  public void onComplete(Response<AiMessage> response) {
                    AiMessage aiMessage = response.content();
                    if (aiMessage == null) {
                      aiMessage = AiMessage.from(assistantContent.toString());
                    }
                    memory.add(aiMessage);
                    sink.complete();
                  }

                  @Override
                  public void onError(Throwable error) {
                    sink.error(error);
                  }
                }));
  }
}
