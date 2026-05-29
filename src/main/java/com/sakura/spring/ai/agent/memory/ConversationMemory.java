package com.sakura.spring.ai.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class ConversationMemory {

    private static final String KEY_PREFIX = "mimo:session:";
    private static final String SESSIONS_KEY = "mimo:sessions";

    private StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int maxMessages;

    // Fallback in-memory store when Redis is unavailable
    private final Map<String, List<Message>> inMemoryFallback = new ConcurrentHashMap<>();
    private volatile boolean redisAvailable = false;

    public ConversationMemory() {
        this.objectMapper = new ObjectMapper();
        this.maxMessages = 50;
    }

    @Autowired(required = false)
    public void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
        if (redis != null) {
            try {
                redis.hasKey("test");
                this.redisAvailable = true;
            } catch (Exception e) {
                this.redisAvailable = false;
            }
        }
    }

    public List<Message> getMessages(String sessionId) {
        if (!redisAvailable) {
            return inMemoryFallback.getOrDefault(sessionId, new ArrayList<>());
        }
        try {
            List<String> jsonList = redis.opsForList().range(KEY_PREFIX + sessionId, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) return new ArrayList<>();
            return jsonList.stream()
                    .map(this::deserializeMessage)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            redisAvailable = false;
            return inMemoryFallback.getOrDefault(sessionId, new ArrayList<>());
        }
    }

    public void addMessage(String sessionId, Message message) {
        if (!redisAvailable) {
            inMemoryFallback.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
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
            redisAvailable = false;
            inMemoryFallback.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
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

    public void clearSession(String sessionId) {
        if (!redisAvailable) {
            inMemoryFallback.remove(sessionId);
            return;
        }
        try {
            redis.delete(KEY_PREFIX + sessionId);
            redis.opsForSet().remove(SESSIONS_KEY, sessionId);
        } catch (Exception e) {
            inMemoryFallback.remove(sessionId);
        }
    }

    public List<String> listSessions() {
        if (!redisAvailable) {
            return new ArrayList<>(inMemoryFallback.keySet());
        }
        try {
            Set<String> members = redis.opsForSet().members(SESSIONS_KEY);
            return members != null ? new ArrayList<>(members) : List.of();
        } catch (Exception e) {
            redisAvailable = false;
            return new ArrayList<>(inMemoryFallback.keySet());
        }
    }

    public boolean hasSession(String sessionId) {
        if (!redisAvailable) return inMemoryFallback.containsKey(sessionId);
        try {
            Boolean has = redis.hasKey(KEY_PREFIX + sessionId);
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
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
                    AssistantMessage msg = new AssistantMessage(text);
                    yield msg;
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
