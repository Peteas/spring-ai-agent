package com.sakura.spring.ai.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);
    private static final String KEY_PREFIX = "mimo:session:";
    private static final String SESSIONS_KEY = "mimo:sessions";
    private static final long REDIS_RETRY_INTERVAL = 60000;
    private static final long SESSION_TTL_DAYS = 7;
    private static final int MAX_IN_MEMORY_SESSIONS = 1000;

    private StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${mimo.agent.max-context-messages:50}")
    private int maxMessages = 50;

    private final Map<String, List<Message>> inMemoryFallback = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> sessionCache = new ConcurrentHashMap<>();
    private final AtomicBoolean redisAvailable = new AtomicBoolean(false);
    private volatile long lastRedisCheckTime = 0;

    public ConversationMemory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        if (!redisAvailable.get() && System.currentTimeMillis() - lastRedisCheckTime > REDIS_RETRY_INTERVAL) {
            synchronized (this) {
                if (System.currentTimeMillis() - lastRedisCheckTime > REDIS_RETRY_INTERVAL) {
                    checkRedisConnection();
                }
            }
        }
        return redisAvailable.get();
    }

    public List<Message> getMessages(String sessionId) {
        List<Message> cached = sessionCache.get(sessionId);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        List<Message> loaded = loadMessagesFromStore(sessionId);
        if (!loaded.isEmpty()) {
            sessionCache.put(sessionId, new CopyOnWriteArrayList<>(loaded));
        }
        return new ArrayList<>(loaded);
    }

    private List<Message> loadMessagesFromStore(String sessionId) {
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
            if (!inMemoryFallback.containsKey(sessionId) && inMemoryFallback.size() >= MAX_IN_MEMORY_SESSIONS) {
                String oldest = inMemoryFallback.keySet().iterator().next();
                inMemoryFallback.remove(oldest);
                sessionCache.remove(oldest);
                log.warn("In-memory session limit reached, evicted session {}", oldest);
            }
            List<Message> messages = inMemoryFallback.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
            messages.add(message);
            while (messages.size() > maxMessages) {
                messages.remove(0);
            }
            sessionCache.put(sessionId, messages);
            return;
        }
        try {
            String json = serializeMessage(message);
            if (json == null) {
                log.warn("Skipping unserializable message for session {}", sessionId);
                return;
            }
            String key = KEY_PREFIX + sessionId;
            redis.opsForList().rightPush(key, json);
            redis.opsForSet().add(SESSIONS_KEY, sessionId);
            redis.expire(key, SESSION_TTL_DAYS, TimeUnit.DAYS);
            Long size = redis.opsForList().size(key);
            if (size != null && size > maxMessages) {
                redis.opsForList().trim(key, size - maxMessages, -1);
            }
            // 增量更新缓存：直接追加，避免全量重新加载
            List<Message> cached = sessionCache.get(sessionId);
            if (cached != null) {
                cached.add(message);
                while (cached.size() > maxMessages) {
                    cached.remove(0);
                }
            } else {
                sessionCache.put(sessionId, new CopyOnWriteArrayList<>(List.of(message)));
            }
        } catch (Exception e) {
            log.error("Failed to add message to Redis for session {}: {}", sessionId, e.getMessage());
            redisAvailable.set(false);
            List<Message> messages = inMemoryFallback.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
            messages.add(message);
            sessionCache.put(sessionId, messages);
        }
    }

    private void refreshCache(String sessionId) {
        List<Message> loaded = loadMessagesFromStore(sessionId);
        sessionCache.put(sessionId, new CopyOnWriteArrayList<>(loaded));
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

    /**
     * Remove the last complete conversation turn (user message and all follow-up assistant/tool messages).
     */
    public void removeLastTurn(String sessionId) {
        List<Message> messages = getMessages(sessionId);
        if (messages.isEmpty()) {
            return;
        }
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0) {
            return;
        }
        removeLastMessages(sessionId, messages.size() - lastUserIdx);
    }

    public void removeLastMessages(String sessionId, int count) {
        if (count <= 0) {
            return;
        }
        if (!isRedisAvailable()) {
            synchronized (inMemoryFallback) {
                List<Message> messages = inMemoryFallback.get(sessionId);
                if (messages != null) {
                    for (int i = 0; i < count && !messages.isEmpty(); i++) {
                        messages.remove(messages.size() - 1);
                    }
                    sessionCache.put(sessionId, messages);
                }
            }
            return;
        }
        try {
            Long size = redis.opsForList().size(KEY_PREFIX + sessionId);
            if (size != null && size >= count) {
                redis.opsForList().trim(KEY_PREFIX + sessionId, 0, size - count - 1);
            }
            refreshCache(sessionId);
        } catch (Exception e) {
            log.error("Failed to remove last {} messages from session {}: {}", count, sessionId, e.getMessage());
            synchronized (inMemoryFallback) {
                List<Message> messages = inMemoryFallback.get(sessionId);
                if (messages != null) {
                    for (int i = 0; i < count && !messages.isEmpty(); i++) {
                        messages.remove(messages.size() - 1);
                    }
                    sessionCache.put(sessionId, messages);
                }
            }
        }
    }

    public void clearSession(String sessionId) {
        sessionCache.remove(sessionId);
        if (!isRedisAvailable()) {
            inMemoryFallback.remove(sessionId);
            return;
        }
        try {
            redis.delete(KEY_PREFIX + sessionId);
            redis.opsForSet().remove(SESSIONS_KEY, sessionId);
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
        if (sessionCache.containsKey(sessionId)) {
            return !sessionCache.get(sessionId).isEmpty();
        }
        if (!isRedisAvailable()) {
            return inMemoryFallback.containsKey(sessionId);
        }
        try {
            Boolean has = redis.hasKey(KEY_PREFIX + sessionId);
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
            log.error("Failed to check session {} in Redis: {}", sessionId, e.getMessage());
            return inMemoryFallback.containsKey(sessionId);
        }
    }

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
            log.error("Failed to serialize message, skipping: {}", e.getMessage());
            try {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("type", message.getMessageType().name());
                fallback.put("text", message.getText() != null ? message.getText() : "");
                return objectMapper.writeValueAsString(fallback);
            } catch (Exception ex) {
                log.error("Fallback serialization also failed: {}", ex.getMessage());
                return null;
            }
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
            log.warn("Failed to deserialize message, wrapping as SystemMessage: {}", e.getMessage());
            return new SystemMessage("[Corrupted message]");
        }
    }
}
