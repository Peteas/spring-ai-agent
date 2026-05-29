package com.sakura.spring.ai.agent.controller;

import com.sakura.spring.ai.agent.MiMoAgent;
import com.sakura.spring.ai.agent.MiMoAgent.AgentEvent;
import com.sakura.spring.ai.agent.memory.ConversationMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final MiMoAgent agent;
    private final ConversationMemory memory;

    public AgentController(MiMoAgent agent, ConversationMemory memory) {
        this.agent = agent;
        this.memory = memory;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> chat(@RequestBody ChatRequest request) {
        String sessionId = request.sessionId() != null ? request.sessionId() : "web-" + UUID.randomUUID();

        return agent.chatStream(sessionId, request.message())
                .map(event -> ServerSentEvent.<Map<String, Object>>builder()
                        .event(event.type().name().toLowerCase())
                        .data(Map.of(
                                "type", event.type().name(),
                                "toolName", event.toolName() != null ? event.toolName() : "",
                                "content", event.content() != null ? event.content() : "",
                                "isError", event.isError()
                        ))
                        .build());
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
