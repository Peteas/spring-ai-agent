package com.sakura.spring.ai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakura.spring.ai.agent.config.AgentProperties;
import com.sakura.spring.ai.agent.memory.ConversationMemory;
import com.sakura.spring.ai.agent.service.ChatLogService;
import com.sakura.spring.ai.agent.tool.Tool;
import com.sakura.spring.ai.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MiMoAgent {

    private static final Logger log = LoggerFactory.getLogger(MiMoAgent.class);

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ConversationMemory memory;
    private final ChatLogService chatLogService;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final ContextManager contextManager;

    public MiMoAgent(ChatModel chatModel, ToolRegistry toolRegistry,
                     ConversationMemory memory, ChatLogService chatLogService,
                     ObjectMapper objectMapper, AgentProperties agentProperties,
                     ContextManager contextManager) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.memory = memory;
        this.chatLogService = chatLogService;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
        this.contextManager = contextManager;
    }

    public Flux<AgentEvent> chatStream(String sessionId, String userMessage) {
        return chatStream(sessionId, userMessage, null);
    }

    /**
     * Streaming chat — returns a Flux of AgentEvents with true token-level streaming.
     */
    public Flux<AgentEvent> chatStream(String sessionId, String userMessage, Long userId) {
        log.info("Chat session {} - User message length: {}", sessionId, userMessage.length());
        List<Message> existing = memory.getMessages(sessionId);
        if (existing.isEmpty() || !(existing.get(existing.size() - 1) instanceof UserMessage um)
                || !um.getText().equals(userMessage)) {
            memory.addUserMessage(sessionId, userMessage);
        }
        String systemPrompt = SystemPrompt.build(agentProperties.getWorkingDirPath());

        List<Message> rawMessages = new ArrayList<>();
        rawMessages.add(new SystemMessage(systemPrompt));
        rawMessages.addAll(memory.getMessages(sessionId));

        // 上下文压缩：当对话过长时自动压缩早期消息
        rawMessages = contextManager.compressIfNeeded(rawMessages);
        List<Message> messages = Collections.synchronizedList(rawMessages);

        List<ToolCallback> toolCallbacks = toolRegistry.getToolCallbacks();

        final int maxRounds = agentProperties.getMaxToolCallRounds();
        final AtomicInteger toolCallRound = new AtomicInteger(0);
        final AtomicInteger totalToolCallCount = new AtomicInteger(0);
        final List<String> allToolsUsed = new CopyOnWriteArrayList<>();
        final AtomicInteger totalPromptTokens = new AtomicInteger(0);
        final AtomicInteger totalCompletionTokens = new AtomicInteger(0);
        final AtomicInteger totalTokens = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        return Flux.defer(() -> {
            List<AssistantMessage.ToolCall> accumulatedToolCalls = new ArrayList<>();
            StringBuilder contentBuilder = new StringBuilder();

            if (toolCallRound.get() >= maxRounds) {
                log.warn("Session {} reached maximum tool call rounds ({})", sessionId, maxRounds);
                return Flux.just(AgentEvent.error("Maximum tool call rounds reached (" + maxRounds + "). Stopping to prevent infinite loop."));
            }

            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .internalToolExecutionEnabled(false)
                    .toolCallbacks(toolCallbacks)
                    .build();
            Prompt prompt = new Prompt(messages, options);

            return chatModel.stream(prompt)
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                            .filter(ex -> {
                                String msg = ex.getMessage();
                                if (msg == null) return false;
                                return msg.contains("GOAWAY")
                                        || msg.contains("Connection reset")
                                        || msg.contains("timeout")
                                        || msg.contains("503")
                                        || msg.contains("502")
                                        || msg.contains("429");
                            })
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .concatMap(response -> {
                        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                            var usage = response.getMetadata().getUsage();
                            if (usage.getPromptTokens() != null) totalPromptTokens.set(usage.getPromptTokens());
                            if (usage.getCompletionTokens() != null) totalCompletionTokens.set(usage.getCompletionTokens());
                            if (usage.getTotalTokens() != null) totalTokens.set(usage.getTotalTokens());
                        }

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
                            toolCallRound.incrementAndGet();
                            log.debug("Session {} - Tool call round {}/{}", sessionId, toolCallRound.get(), maxRounds);

                            AssistantMessage toolCallMsg = AssistantMessage.builder()
                                    .content(contentBuilder.toString())
                                    .toolCalls(accumulatedToolCalls)
                                    .build();
                            messages.add(toolCallMsg);
                            memory.addMessage(sessionId, toolCallMsg);

                            int maxParallel = agentProperties.getMaxParallelToolCalls();
                            return Flux.fromIterable(accumulatedToolCalls)
                                    .flatMap(tc -> Mono.fromCallable(() -> executeTool(sessionId, tc))
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .flatMapMany(result -> {
                                                messages.add(result.toolResponseMessage());
                                                memory.addMessage(sessionId, result.toolResponseMessage());
                                                allToolsUsed.add(tc.name());
                                                totalToolCallCount.incrementAndGet();
                                                return Flux.just(
                                                        AgentEvent.toolCall(tc.name(), tc.arguments()),
                                                        AgentEvent.toolResult(tc.name(), result.output(), result.isError())
                                                );
                                            }),
                                            maxParallel
                                    );
                        } else {
                            String answer = contentBuilder.toString();
                            if (!answer.isEmpty()) {
                                memory.addAssistantMessage(sessionId, answer);
                                log.info("Session {} - Response length: {}, tokens: prompt={}, completion={}, total={}",
                                        sessionId, answer.length(),
                                        totalPromptTokens.get(), totalCompletionTokens.get(), totalTokens.get());
                            }
                            long latency = System.currentTimeMillis() - startTime;
                            chatLogService.saveChatLog(userId, sessionId, userMessage, answer,
                                    totalPromptTokens.get(), totalCompletionTokens.get(), totalTokens.get(),
                                    List.copyOf(allToolsUsed), totalToolCallCount.get(), toolCallRound.get(),
                                    latency, agentProperties.getModel(), null);

                            return Flux.just(AgentEvent.doneWithUsage(
                                    totalPromptTokens.get(), totalCompletionTokens.get(), totalTokens.get()));
                        }
                    }));
        })
        .repeat()
        .takeUntil(event -> event.type() == AgentEvent.EventType.DONE
                || event.type() == AgentEvent.EventType.ERROR)
        .onErrorResume(e -> {
            log.error("Session {} - Agent error: {}", sessionId, e.getMessage(), e);
            long latency = System.currentTimeMillis() - startTime;
            chatLogService.saveChatLog(userId, sessionId, userMessage, null,
                    totalPromptTokens.get(), totalCompletionTokens.get(), totalTokens.get(),
                    List.copyOf(allToolsUsed), totalToolCallCount.get(), toolCallRound.get(),
                    latency, agentProperties.getModel(), e.getMessage());
            return Flux.just(AgentEvent.error("Agent error: " + e.getMessage()));
        });
    }

    private ToolExecutionResult executeTool(String sessionId, AssistantMessage.ToolCall tc) {
        Map<String, Object> parsedArgs = parseArguments(tc.arguments());
        Tool.ToolResult result;
        if (parsedArgs == null) {
            result = Tool.ToolResult.error("Invalid JSON arguments for tool: " + tc.arguments());
        } else {
            if ("todo".equals(tc.name())) {
                parsedArgs = new HashMap<>(parsedArgs);
                parsedArgs.putIfAbsent("sessionId", sessionId);
            }

            // 权限等级审计日志
            Tool.PermissionLevel level = toolRegistry.getPermissionLevel(tc.name(), parsedArgs);
            if (level == Tool.PermissionLevel.DESTRUCTIVE) {
                log.warn("Session {} - DESTRUCTIVE tool call: {} | args: {}", sessionId, tc.name(), tc.arguments());
            } else if (level == Tool.PermissionLevel.WRITE) {
                log.info("Session {} - WRITE tool call: {} | args: {}", sessionId, tc.name(), tc.arguments());
            }

            log.debug("Session {} - Executing tool: {}", sessionId, tc.name());
            result = toolRegistry.execute(tc.name(), parsedArgs);
        }
        ToolResponseMessage toolRespMsg = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        tc.id(), tc.name(), result.output())))
                .build();
        return new ToolExecutionResult(toolRespMsg, result.output(), result.isError());
    }

    private record ToolExecutionResult(ToolResponseMessage toolResponseMessage, String output, boolean isError) {}

    public AgentResponse chat(String sessionId, String userMessage, AgentCallback callback) {
        return chat(sessionId, userMessage, null, callback);
    }

    public AgentResponse chat(String sessionId, String userMessage, Long userId, AgentCallback callback) {
        StringBuilder answerBuilder = new StringBuilder();
        boolean[] success = {false};

        chatStream(sessionId, userMessage, userId)
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
                        case CONFIRMATION_REQUIRED -> {
                            if (callback != null) callback.onConfirmationRequired(event.toolName(), event.content());
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
            return null;
        }
    }

    public void clearSession(String sessionId) {
        memory.clearSession(sessionId);
    }

    public List<String> listSessions() {
        return memory.listSessions();
    }

    public interface AgentCallback {
        default void onToolCall(String toolName, String arguments) {}
        default void onToolResult(String toolName, String output, boolean isError) {}
        default void onAnswer(String answer) {}
        default void onAnswerChunk(String chunk) {}
        default void onThinking(String content) {}
        default void onError(String error) {}
        default void onConfirmationRequired(String toolName, String reason) {}
    }

    public record AgentResponse(String content, boolean success) {}

    public record AgentEvent(EventType type, String toolName, String content, boolean isError,
                             int promptTokens, int completionTokens, int totalTokens) {
        public enum EventType { THINKING, TOOL_CALL, TOOL_RESULT, ANSWER_CHUNK, DONE, ERROR, CONFIRMATION_REQUIRED }

        public static AgentEvent thinking(String content) {
            return new AgentEvent(EventType.THINKING, null, content, false, 0, 0, 0);
        }

        public static AgentEvent toolCall(String name, String args) {
            return new AgentEvent(EventType.TOOL_CALL, name, args, false, 0, 0, 0);
        }

        public static AgentEvent toolResult(String name, String output, boolean isError) {
            return new AgentEvent(EventType.TOOL_RESULT, name, output, isError, 0, 0, 0);
        }

        public static AgentEvent answerChunk(String content) {
            return new AgentEvent(EventType.ANSWER_CHUNK, null, content, false, 0, 0, 0);
        }

        public static AgentEvent done() {
            return new AgentEvent(EventType.DONE, null, "", false, 0, 0, 0);
        }

        public static AgentEvent doneWithUsage(int promptTokens, int completionTokens, int totalTokens) {
            return new AgentEvent(EventType.DONE, null, "", false, promptTokens, completionTokens, totalTokens);
        }

        public static AgentEvent error(String message) {
            return new AgentEvent(EventType.ERROR, null, message, true, 0, 0, 0);
        }

        public static AgentEvent confirmationRequired(String toolName, String args, String reason) {
            return new AgentEvent(EventType.CONFIRMATION_REQUIRED, toolName, reason + "\nArgs: " + args, false, 0, 0, 0);
        }
    }
}
