package com.sakura.spring.ai.agent.controller;

import com.sakura.spring.ai.agent.MiMoAgent;
import com.sakura.spring.ai.agent.MiMoAgent.AgentEvent;
import com.sakura.spring.ai.agent.memory.ConversationMemory;
import com.sakura.spring.ai.agent.security.JwtUserDetails;
import com.sakura.spring.ai.agent.service.UserService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class AgentController {

    private static final int MAX_MESSAGE_LENGTH = 10000;
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9\\-]{1,64}$");

    private final MiMoAgent agent;
    private final ConversationMemory memory;
    private final UserService userService;

    public AgentController(MiMoAgent agent, ConversationMemory memory, UserService userService) {
        this.agent = agent;
        this.memory = memory;
        this.userService = userService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> chat(@RequestBody ChatRequest request,
                                  @AuthenticationPrincipal JwtUserDetails userDetails) {
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

        // 关联 session 到用户
        if (userDetails != null) {
            userService.associateSession(userDetails.getUserId(), sessionId);
        }

        final String finalSessionId = sessionId;

        // 创建心跳流，每 30 秒发送一次心跳
        Flux<ServerSentEvent<Map<String, Object>>> heartbeat = Flux.interval(Duration.ofSeconds(30))
                .map(i -> ServerSentEvent.<Map<String, Object>>builder()
                        .comment("heartbeat")
                        .build());

        // 创建消息流
        Flux<ServerSentEvent<Map<String, Object>>> messageStream = agent.chatStream(finalSessionId, request.message())
                .map(event -> ServerSentEvent.<Map<String, Object>>builder()
                        .event(event.type().name().toLowerCase())
                        .data(Map.of(
                                "type", event.type().name(),
                                "toolName", event.toolName() != null ? event.toolName() : "",
                                "content", event.content() != null ? event.content() : "",
                                "isError", event.isError()
                        ))
                        .build());

        // 合并心跳和消息流
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(Flux.merge(heartbeat, messageStream));
    }

    @GetMapping("/sessions")
    public List<String> listSessions(@AuthenticationPrincipal JwtUserDetails userDetails) {
        if (userDetails != null) {
            return userService.getUserSessions(userDetails.getUserId());
        }
        return agent.listSessions();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId,
                                             @AuthenticationPrincipal JwtUserDetails userDetails) {
        agent.clearSession(sessionId);
        if (userDetails != null) {
            userService.removeSession(userDetails.getUserId(), sessionId);
        }
        return Map.of("status", "ok", "sessionId", sessionId);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> getMessages(@PathVariable String sessionId,
                                         @AuthenticationPrincipal JwtUserDetails userDetails) {
        // 验证 session 所有权
        if (userDetails != null && !userService.isSessionOwner(userDetails.getUserId(), sessionId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }

        return ResponseEntity.ok(memory.getMessages(sessionId).stream()
                .map(m -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    if (m instanceof UserMessage) {
                        msg.put("role", "user");
                        msg.put("content", m.getText() != null ? m.getText() : "");
                    } else if (m instanceof AssistantMessage am) {
                        msg.put("role", "assistant");
                        msg.put("content", m.getText() != null ? m.getText() : "");
                        if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                            msg.put("toolCalls", am.getToolCalls().stream()
                                    .map(tc -> Map.of(
                                            "id", tc.id(),
                                            "name", tc.name(),
                                            "arguments", tc.arguments()
                                    )).toList());
                        }
                    } else if (m instanceof ToolResponseMessage trm) {
                        msg.put("role", "tool");
                        msg.put("content", trm.getResponses().stream()
                                .map(r -> Map.of(
                                        "id", r.id(),
                                        "name", r.name(),
                                        "result", r.responseData()
                                )).toList());
                    } else {
                        msg.put("role", "system");
                        msg.put("content", m.getText() != null ? m.getText() : "");
                    }
                    return msg;
                })
                .toList());
    }

    public record ChatRequest(String message, String sessionId) {}
}
