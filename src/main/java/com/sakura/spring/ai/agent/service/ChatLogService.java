package com.sakura.spring.ai.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakura.spring.ai.agent.mapper.ChatLogMapper;
import com.sakura.spring.ai.agent.model.ChatLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatLogService {

    private static final Logger log = LoggerFactory.getLogger(ChatLogService.class);

    private final ChatLogMapper chatLogMapper;
    private final ObjectMapper objectMapper;

    public ChatLogService(ChatLogMapper chatLogMapper) {
        this.chatLogMapper = chatLogMapper;
        this.objectMapper = new ObjectMapper();
    }

    @Async
    public void saveChatLog(Long userId, String sessionId, String userMessage, String assistantMessage,
                            int promptTokens, int completionTokens, int totalTokens,
                            List<String> toolsUsed, int toolCallCount, int roundCount,
                            long latencyMs, String model, String error) {
        try {
            ChatLog chatLog = new ChatLog();
            chatLog.setUserId(userId);
            chatLog.setSessionId(sessionId);
            chatLog.setUserMessage(userMessage);
            chatLog.setAssistantMessage(assistantMessage);
            chatLog.setPromptTokens(promptTokens);
            chatLog.setCompletionTokens(completionTokens);
            chatLog.setTotalTokens(totalTokens);
            chatLog.setToolsUsed(toolsUsed != null ? objectMapper.writeValueAsString(toolsUsed) : "[]");
            chatLog.setToolCallCount(toolCallCount);
            chatLog.setRoundCount(roundCount);
            chatLog.setLatencyMs(latencyMs);
            chatLog.setModel(model);
            chatLog.setError(error);
            chatLogMapper.insert(chatLog);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize tools_used: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to save chat log for session {}: {}", sessionId, e.getMessage());
        }
    }
}
