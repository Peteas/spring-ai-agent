package com.sakura.spring.ai.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);
    private static final String KEY_PREFIX = "mimo:session:";
    private static final String SESSIONS_KEY = "mimo:sessions";
    private static final long REDIS_RETRY_INTERVAL = 60000; // 1 minute

    private StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${mimo.agent.max-context-messages:50}")
    private int maxMessages;

    // Fallback in-memory store when Redis is unavailable
    private final Map<String, List<Message>> inMemoryFallback = new ConcurrentHashMap<>();
    private final AtomicBoolean redisAvailable = new AtomicBoolean(false);
    private volatile long lastRedisCheckTime = 0;

    public ConversationMemory() {
        this.objectMapper = new ObjectMapper();
    }

    @Autowired(required = false)
    public void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
        checkRedisConnection();
    }

    private void checkRedisConnection() {
        if (redis == null) {
            redisAvailable.set(false);
            return;
        }
        try {
            redis.hasKey(KEY_PREFIX + "connection_test");
            redisAvailable.set(true);
            lastRedisCheckTime = System.currentTimeMillis();
            log.info("Redis connection established");
        } catch (Exception e) {
            redisAvailable.set(false);
            log.warn("Redis connection failed, using in-memory fallback: {}", e.getMessage());
        }
    }

    private boolean isRedisAvailable() {
        if (redis == null) return false;
        // 尝试重连
        if (!redisAvailable.get() && System.currentTimeMillis() - lastRedisCheckTime > REDIS_RETRY_INTERVAL) {
            checkRedisConnection();
        }
        return redisAvailable.get();
    }

    public List<Message> getMessages(String sessionId) {
        if (!isRedisAvailable()) {
            return new ArrayList<>(inMemoryFallback.getOrDefault(sessionId, new CopyOnWriteArrayList<>()));
        }
        try {
            List<String> jsonList = redis.opsForList().range(KEY_PREFIX + sessionId, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) return new ArrayList<>();
            return jsonList.stream()
                    .map(this::deserializeMessage)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            log.error("Failed to get messages from Redis for session {}: {}", sessionId, e.getMessage());
            redisAvailable.set(false);
            return new ArrayList<>(inMemoryFallback.getOrDefault(sessionId, new CopyOnWriteArrayList<>()));
        }
    }

    public void addMessage(String sessionId, Message message) {
        if (!isRedisAvailable()) {
            inMemoryFallback.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(message);
            log.debug("Added message to in-memory store for session {}", sessionId);
            return;
        }
        try {
            String json = serializeMessage(message);
            redis.opsForList().rightPush(KEY_PREFIX + sessionId, json);
            // Track session
            redis.opsForSet().add(SESSIONS_KEY, sessionId);
            // Trim if needed
            Long size = redis.opsForList().size(KEY_PREFIX + sessionId);
            if (size != null && size > maxMessages) {
                redis.opsForList().trim(KEY_PREFIX + sessionId, size - maxMessages, -1);
            }
        } catch (Exception e) {
            log.error("Failed to add message to Redis for session {}: {}", sessionId, e.getMessage());
            redisAvailable.set(false);
            inMemoryFallback.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(message);
        }
    }

    public void addUserMessage(String sessionId, String content) {
        addMessage(sessionId, new UserMessage(content));
    }

    public void addAssistantMessage(String sessionId, String content) {
        addMessage(sessionId, new AssistantMessage(content));
    }

    public void addToolCallResult(String sessionId, String toolCallId, String toolName, String result) {
        addMessage(sessionId, ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(toolCallId, toolName, result)))
                .metadata(Map.of())
                .build());
    }

    public void removeLastMessages(String sessionId, int count) {
        if (!isRedisAvailable()) {
            List<Message> messages = inMemoryFallback.get(sessionId);
            if (messages != null && messages.size() >= count) {
                for (int i = 0; i < count; i++) {
                    messages.remove(messages.size() - 1);
                }
            }
            return;
        }
        try {
            Long size = redis.opsForList().size(KEY_PREFIX + sessionId);
            if (size != null && size >= count) {
                redis.opsForList().trim(KEY_PREFIX + sessionId, 0, size - count - 1);
            }
        } catch (Exception e) {
            log.error("Failed to remove last {} messages from session {}: {}", count, sessionId, e.getMessage());
            List<Message> messages = inMemoryFallback.get(sessionId);
            if (messages != null && messages.size() >= count) {
                for (int i = 0; i < count; i++) {
                    messages.remove(messages.size() - 1);
                }
            }
        }
    }

    public void clearSession(String sessionId) {
        if (!isRedisAvailable()) {
            inMemoryFallback.remove(sessionId);
            return;
        }
        try {
            redis.delete(KEY_PREFIX + sessionId);
            redis.opsForSet().remove(SESSIONS_KEY, sessionId);
            log.debug("Cleared session {} from Redis", sessionId);
        } catch (Exception e) {
            log.error("Failed to clear session {} from Redis: {}", sessionId, e.getMessage());
            inMemoryFallback.remove(sessionId);
        }
    }

    public List<String> listSessions() {
        if (!isRedisAvailable()) {
            return new ArrayList<>(inMemoryFallback.keySet());
        }
        try {
            Set<String> members = redis.opsForSet().members(SESSIONS_KEY);
            return members != null ? new ArrayList<>(members) : List.of();
        } catch (Exception e) {
            log.error("Failed to list sessions from Redis: {}", e.getMessage());
            redisAvailable.set(false);
            return new ArrayList<>(inMemoryFallback.keySet());
        }
    }

    public boolean hasSession(String sessionId) {
        if (!isRedisAvailable()) return inMemoryFallback.containsKey(sessionId);
        try {
            Boolean has = redis.hasKey(KEY_PREFIX + sessionId);
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
            log.error("Failed to check session {} in Redis: {}", sessionId, e.getMessage());
            return inMemoryFallback.containsKey(sessionId);
        }
    }

    // --- Serialization helpers ---

    private String serializeMessage(Message message) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", message.getMessageType().name());
            data.put("text", message.getText());

            if (message instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                data.put("toolCalls", am.getToolCalls().stream().map(tc -> Map.of(
                        "id", tc.id(),
                        "type", tc.type(),
                        "name", tc.name(),
                        "arguments", tc.arguments()
                )).toList());
            }

            if (message instanceof ToolResponseMessage trm) {
                data.put("responses", trm.getResponses().stream().map(r -> Map.of(
                        "id", r.id(),
                        "name", r.name(),
                        "responseData", r.responseData()
                )).toList());
            }

            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"USER\",\"text\":\"" + message.getText().replace("\"", "\\\"") + "\"}";
        }
    }

    private Message deserializeMessage(String json) {
        try {
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
            String type = (String) data.get("type");
            String text = (String) data.getOrDefault("text", "");

            return switch (type) {
                case "USER" -> new UserMessage(text);
                case "ASSISTANT" -> {
                    List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) data.get("toolCalls");
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        List<AssistantMessage.ToolCall> tcList = toolCalls.stream()
                                .map(tc -> new AssistantMessage.ToolCall(
                                        (String) tc.get("id"),
                                        (String) tc.getOrDefault("type", "function"),
                                        (String) tc.get("name"),
                                        (String) tc.get("arguments")
                                )).toList();
                        yield AssistantMessage.builder()
                                .content(text)
                                .toolCalls(tcList)
                                .build();
                    }
                    yield new AssistantMessage(text);
                }
                case "SYSTEM" -> new SystemMessage(text);
                case "TOOL" -> {
                    List<Map<String, Object>> responses = (List<Map<String, Object>>) data.get("responses");
                    if (responses != null) {
                        List<ToolResponseMessage.ToolResponse> toolResponses = responses.stream()
                                .map(r -> new ToolResponseMessage.ToolResponse(
                                        (String) r.get("id"),
                                        (String) r.get("name"),
                                        (String) r.get("responseData")
                                )).toList();
                        yield ToolResponseMessage.builder()
                                .responses(toolResponses)
                                .metadata(Map.of())
                                .build();
                    }
                    yield new UserMessage(text);
                }
                default -> new UserMessage(text);
            };
        } catch (Exception e) {
            return new UserMessage(json);
        }
    }
}
