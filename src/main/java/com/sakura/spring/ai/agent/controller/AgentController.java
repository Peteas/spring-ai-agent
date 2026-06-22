package com.sakura.spring.ai.agent.controller;

import com.sakura.spring.ai.agent.MiMoAgent;
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
import reactor.core.publisher.Mono;

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
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Message is required"));
        }
        if (request.message().length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Message too long. Maximum length: " + MAX_MESSAGE_LENGTH));
        }

        boolean sessionIdProvided = request.sessionId() != null && !request.sessionId().isBlank();
        String sessionId = sessionIdProvided ? request.sessionId() : "web-" + UUID.randomUUID();

        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid sessionId format. Use alphanumeric characters and hyphens, max 64 chars"));
        }

        if (sessionIdProvided) {
            ResponseEntity<?> accessDenied = checkSessionAccess(sessionId, userDetails);
            if (accessDenied != null) {
                return accessDenied;
            }
        }

        if (Boolean.TRUE.equals(request.regenerate())) {
            memory.removeLastTurn(sessionId);
        }

        Long userId = userDetails != null ? userDetails.getUserId() : null;
        if (userId != null) {
            userService.associateSession(userId, sessionId);
        }

        final String finalSessionId = sessionId;

        Flux<ServerSentEvent<Map<String, Object>>> messageStream = agent.chatStream(finalSessionId, request.message(), userId)
                .map(event -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", event.type().name());
                    data.put("toolName", event.toolName() != null ? event.toolName() : "");
                    data.put("content", event.content() != null ? event.content() : "");
                    data.put("isError", event.isError());
                    if (event.type() == MiMoAgent.AgentEvent.EventType.DONE) {
                        data.put("promptTokens", event.promptTokens());
                        data.put("completionTokens", event.completionTokens());
                        data.put("totalTokens", event.totalTokens());
                    }
                    return ServerSentEvent.<Map<String, Object>>builder()
                            .event(event.type().name().toLowerCase())
                            .data(data)
                            .build();
                })
                .cache();

        Mono<Void> streamTerminated = messageStream.then(Mono.<Void>empty()).onErrorResume(e -> Mono.empty());
        Flux<ServerSentEvent<Map<String, Object>>> heartbeat = Flux.interval(Duration.ofSeconds(30))
                .takeUntilOther(streamTerminated)
                .map(i -> ServerSentEvent.<Map<String, Object>>builder()
                        .comment("heartbeat")
                        .build());

        Flux<ServerSentEvent<Map<String, Object>>> merged = Flux.merge(heartbeat, messageStream)
                .onErrorResume(e -> {
                    Map<String, Object> errorData = Map.of(
                            "type", "ERROR",
                            "toolName", "",
                            "content", "Stream error: " + e.getMessage(),
                            "isError", true
                    );
                    return Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                            .event("error")
                            .data(errorData)
                            .build());
                });

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(merged);
    }

    private ResponseEntity<?> checkSessionAccess(String sessionId, JwtUserDetails userDetails) {
        if (!memory.hasSession(sessionId) && !userService.isSessionOwnedByAnyone(sessionId)) {
            return null;
        }
        if (userDetails != null) {
            if (userService.isSessionOwnedByAnyone(sessionId)
                    && !userService.isSessionOwner(userDetails.getUserId(), sessionId)) {
                return ResponseEntity.status(403).body(ApiResponse.error(403, "Access denied"));
            }
        } else if (userService.isSessionOwnedByAnyone(sessionId)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "Authentication required for this session"));
        }
        return null;
    }

    @GetMapping("/sessions")
    public List<String> listSessions(@AuthenticationPrincipal JwtUserDetails userDetails) {
        if (userDetails != null) {
            return userService.getUserSessions(userDetails.getUserId());
        }
        return agent.listSessions();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> deleteSession(@PathVariable String sessionId,
                                             @AuthenticationPrincipal JwtUserDetails userDetails) {
        if (!isValidSessionId(sessionId)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid sessionId format"));
        }
        if (userDetails != null && userService.isSessionOwnedByAnyone(sessionId)
                && !userService.isSessionOwner(userDetails.getUserId(), sessionId)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "Access denied"));
        }
        agent.clearSession(sessionId);
        if (userDetails != null) {
            userService.removeSession(userDetails.getUserId(), sessionId);
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("sessionId", sessionId)));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> getMessages(@PathVariable String sessionId,
                                         @AuthenticationPrincipal JwtUserDetails userDetails) {
        if (!isValidSessionId(sessionId)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid sessionId format"));
        }
        if (userDetails != null && userService.isSessionOwnedByAnyone(sessionId)
                && !userService.isSessionOwner(userDetails.getUserId(), sessionId)) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, "Access denied"));
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

    private boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID_PATTERN.matcher(sessionId).matches();
    }

    public record ChatRequest(String message, String sessionId, Boolean regenerate) {}
}
