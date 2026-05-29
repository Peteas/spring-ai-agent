package com.sakura.spring.ai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakura.spring.ai.agent.memory.ConversationMemory;
import com.sakura.spring.ai.agent.tool.Tool;
import com.sakura.spring.ai.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@Service
public class MiMoAgent {

    private static final Logger log = LoggerFactory.getLogger(MiMoAgent.class);
    private static final int MAX_TOOL_CALL_ROUNDS = 20;

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ConversationMemory memory;
    private final ObjectMapper objectMapper;

    public MiMoAgent(ChatModel chatModel, ToolRegistry toolRegistry,
                     ConversationMemory memory) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.memory = memory;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Streaming chat — returns a Flux of AgentEvents with true token-level streaming.
     */
    public Flux<AgentEvent> chatStream(String sessionId, String userMessage) {
        log.info("Chat session {} - User message length: {}", sessionId, userMessage.length());
        memory.addUserMessage(sessionId, userMessage);
        String systemPrompt = SystemPrompt.build(Path.of(System.getProperty("user.dir")));

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(memory.getMessages(sessionId));
        List<ToolCallback> toolCallbacks = toolRegistry.getToolCallbacks();

        final int[] toolCallRound = {0};

        return Flux.defer(() -> {
            List<AssistantMessage.ToolCall> accumulatedToolCalls = new ArrayList<>();
            StringBuilder contentBuilder = new StringBuilder();

            // 检查工具调用轮次限制
            if (toolCallRound[0] >= MAX_TOOL_CALL_ROUNDS) {
                log.warn("Session {} reached maximum tool call rounds ({})", sessionId, MAX_TOOL_CALL_ROUNDS);
                return Flux.just(AgentEvent.error("Maximum tool call rounds reached (" + MAX_TOOL_CALL_ROUNDS + "). Stopping to prevent infinite loop."));
            }

            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .internalToolExecutionEnabled(false)
                    .toolCallbacks(toolCallbacks)
                    .build();
            Prompt prompt = new Prompt(messages, options);

            return chatModel.stream(prompt)
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                            .filter(ex -> ex.getMessage() != null && ex.getMessage().contains("GOAWAY"))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .concatMap(response -> {
                        if (response.getResult() == null) return Flux.empty();
                        AssistantMessage output = response.getResult().getOutput();
                        List<AgentEvent> events = new ArrayList<>();

                        String text = output.getText();
                        if (text != null && !text.isEmpty()) {
                            contentBuilder.append(text);
                            events.add(AgentEvent.answerChunk(text));
                        }

                        if (output.getToolCalls() != null) {
                            for (var tc : output.getToolCalls()) {
                                if (accumulatedToolCalls.stream().noneMatch(e -> e.id().equals(tc.id()))) {
                                    accumulatedToolCalls.add(tc);
                                }
                            }
                        }

                        return Flux.fromIterable(events);
                    })
                    .concatWith(Flux.defer(() -> {
                        if (!accumulatedToolCalls.isEmpty()) {
                            toolCallRound[0]++;
                            log.debug("Session {} - Tool call round {}/{}", sessionId, toolCallRound[0], MAX_TOOL_CALL_ROUNDS);

                            messages.add(AssistantMessage.builder()
                                    .content(contentBuilder.toString())
                                    .toolCalls(accumulatedToolCalls)
                                    .build());

                            List<AgentEvent> toolEvents = new ArrayList<>();
                            for (var tc : accumulatedToolCalls) {
                                log.debug("Session {} - Executing tool: {}", sessionId, tc.name());
                                toolEvents.add(AgentEvent.toolCall(tc.name(), tc.arguments()));
                                Tool.ToolResult result = toolRegistry.execute(tc.name(),
                                        parseArguments(tc.arguments()));
                                toolEvents.add(AgentEvent.toolResult(tc.name(),
                                        result.output(), result.isError()));
                                messages.add(ToolResponseMessage.builder()
                                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                                tc.id(), tc.name(), result.output())))
                                        .build());
                            }
                            return Flux.fromIterable(toolEvents);
                        } else {
                            String answer = contentBuilder.toString();
                            if (!answer.isEmpty()) {
                                memory.addAssistantMessage(sessionId, answer);
                                log.info("Session {} - Response length: {}", sessionId, answer.length());
                            }
                            return Flux.just(AgentEvent.done());
                        }
                    }));
        })
        .repeat()
        .takeUntil(event -> event.type() == AgentEvent.EventType.DONE
                || event.type() == AgentEvent.EventType.ERROR)
        .onErrorResume(e -> {
            log.error("Session {} - Agent error: {}", sessionId, e.getMessage(), e);
            return Flux.just(AgentEvent.error("Agent error: " + e.getMessage()));
        });
    }

    /**
     * Synchronous chat — collects the full streaming response.
     */
    public AgentResponse chat(String sessionId, String userMessage, AgentCallback callback) {
        StringBuilder answerBuilder = new StringBuilder();
        boolean[] success = {false};

        chatStream(sessionId, userMessage)
                .doOnNext(event -> {
                    switch (event.type()) {
                        case ANSWER_CHUNK -> {
                            answerBuilder.append(event.content());
                            if (callback != null) callback.onAnswerChunk(event.content());
                        }
                        case TOOL_CALL -> {
                            if (callback != null) callback.onToolCall(event.toolName(), event.content());
                        }
                        case TOOL_RESULT -> {
                            if (callback != null) callback.onToolResult(event.toolName(), event.content(), event.isError());
                        }
                        case THINKING -> {
                            if (callback != null) callback.onThinking(event.content());
                        }
                        case DONE -> {
                            success[0] = true;
                            if (callback != null) callback.onAnswer(answerBuilder.toString());
                        }
                        case ERROR -> {
                            if (callback != null) callback.onError(event.content());
                        }
                    }
                })
                .blockLast();

        return new AgentResponse(answerBuilder.toString(), success[0]);
    }

    private Map<String, Object> parseArguments(String json) {
        try {
            if (json == null || json.isBlank()) return Map.of();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", e.getMessage());
            return Map.of();
        }
    }

    public void clearSession(String sessionId) {
        memory.clearSession(sessionId);
    }

    public List<String> listSessions() {
        return memory.listSessions();
    }

    // Callback interface for synchronous usage (CLI)
    public interface AgentCallback {
        default void onToolCall(String toolName, String arguments) {}
        default void onToolResult(String toolName, String output, boolean isError) {}
        default void onAnswer(String answer) {}
        default void onAnswerChunk(String chunk) {}
        default void onThinking(String content) {}
        default void onError(String error) {}
    }

    public record AgentResponse(String content, boolean success) {}

    public record AgentEvent(EventType type, String toolName, String content, boolean isError) {
        public enum EventType { THINKING, TOOL_CALL, TOOL_RESULT, ANSWER_CHUNK, DONE, ERROR }

        public static AgentEvent thinking(String content) {
            return new AgentEvent(EventType.THINKING, null, content, false);
        }

        public static AgentEvent toolCall(String name, String args) {
            return new AgentEvent(EventType.TOOL_CALL, name, args, false);
        }

        public static AgentEvent toolResult(String name, String output, boolean isError) {
            return new AgentEvent(EventType.TOOL_RESULT, name, output, isError);
        }

        public static AgentEvent answerChunk(String content) {
            return new AgentEvent(EventType.ANSWER_CHUNK, null, content, false);
        }

        public static AgentEvent done() {
            return new AgentEvent(EventType.DONE, null, "", false);
        }

        public static AgentEvent error(String message) {
            return new AgentEvent(EventType.ERROR, null, message, true);
        }
    }
}
