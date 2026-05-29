package com.sakura.spring.ai.agent.controller;

import com.sakura.spring.ai.agent.MiMoAgent;
import com.sakura.spring.ai.agent.MiMoAgent.AgentEvent;
import com.sakura.spring.ai.agent.memory.ConversationMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class AgentController {

    private static final int MAX_MESSAGE_LENGTH = 10000;
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9\\-]{1,64}$");

    private final MiMoAgent agent;
    private final ConversationMemory memory;

    public AgentController(MiMoAgent agent, ConversationMemory memory) {
        this.agent = agent;
        this.memory = memory;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        // 校验 message 长度
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }
        if (request.message().length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message too long. Maximum length: " + MAX_MESSAGE_LENGTH));
        }

        // 生成或校验 sessionId
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "web-" + UUID.randomUUID();
        } else if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid sessionId format. Use alphanumeric characters and hyphens, max 64 chars"));
        }

        final String finalSessionId = sessionId;
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(agent.chatStream(finalSessionId, request.message())
                        .map(event -> ServerSentEvent.<Map<String, Object>>builder()
                                .event(event.type().name().toLowerCase())
                                .data(Map.of(
                                        "type", event.type().name(),
                                        "toolName", event.toolName() != null ? event.toolName() : "",
                                        "content", event.content() != null ? event.content() : "",
                                        "isError", event.isError()
                                ))
                                .build()));
    }

    @GetMapping("/sessions")
    public List<String> listSessions() {
        return agent.listSessions();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        agent.clearSession(sessionId);
        return Map.of("status", "ok", "sessionId", sessionId);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<Map<String, String>> getMessages(@PathVariable String sessionId) {
        return memory.getMessages(sessionId).stream()
                .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                .map(m -> Map.of(
                        "role", m instanceof UserMessage ? "user" : "assistant",
                        "content", m.getText() != null ? m.getText() : ""
                ))
                .toList();
    }

    public record ChatRequest(String message, String sessionId) {}
}
