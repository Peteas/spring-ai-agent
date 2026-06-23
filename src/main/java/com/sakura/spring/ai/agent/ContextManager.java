package com.sakura.spring.ai.agent;

import com.sakura.spring.ai.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文窗口管理器 — 在对话历史过长时自动压缩早期消息为摘要
 */
@Component
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final ChatModel chatModel;
    private final AgentProperties agentProperties;

    public ContextManager(ChatModel chatModel, AgentProperties agentProperties) {
        this.chatModel = chatModel;
        this.agentProperties = agentProperties;
    }

    /**
     * 估算消息列表的 token 数
     * 简单方案：中文约 1.5 字/token，英文约 4 字符/token
     */
    public int estimateTokens(List<Message> messages) {
        int totalChars = 0;
        for (Message msg : messages) {
            String text = msg.getText();
            if (text != null) {
                totalChars += text.length();
            }
            // 工具调用 JSON 也计入
            if (msg instanceof AssistantMessage am && am.getToolCalls() != null) {
                for (var tc : am.getToolCalls()) {
                    totalChars += tc.arguments() != null ? tc.arguments().length() : 0;
                }
            }
            if (msg instanceof ToolResponseMessage trm) {
                for (var r : trm.getResponses()) {
                    totalChars += r.responseData() != null ? r.responseData().length() : 0;
                }
            }
        }
        // 粗略估算：按 2 字符/token（中英混合折中）
        return totalChars / 2;
    }

    /**
     * 如果上下文过长，压缩早期消息为摘要
     *
     * @param messages 原始消息列表（第一条为 SystemMessage）
     * @return 压缩后的消息列表
     */
    public List<Message> compressIfNeeded(List<Message> messages) {
        int maxTokens = agentProperties.getMaxContextTokens();
        int keepRecent = agentProperties.getKeepRecentTurns() * 2; // 每轮 = user + assistant

        int estimatedTokens = estimateTokens(messages);

        // 不需要压缩
        if (estimatedTokens < maxTokens * 0.8) {
            return messages;
        }

        log.info("Context too large ({} tokens estimated, max {}), compressing...",
                estimatedTokens, maxTokens);

        // 确保有足够的消息需要压缩
        if (messages.size() <= keepRecent + 2) {
            log.warn("Not enough messages to compress, keeping as-is");
            return messages;
        }

        // 分离：system prompt + 早期消息 + 最近消息
        Message systemPrompt = messages.get(0);
        List<Message> earlyMessages = messages.subList(1, messages.size() - keepRecent);
        List<Message> recentMessages = messages.subList(messages.size() - keepRecent, messages.size());

        // 生成摘要
        String summary = summarize(earlyMessages);
        if (summary == null || summary.isBlank()) {
            log.warn("Summarization failed, keeping original messages");
            return messages;
        }

        // 构建压缩后的消息列表
        List<Message> compressed = new ArrayList<>();
        compressed.add(systemPrompt);
        compressed.add(new UserMessage("[Previous conversation summary]: " + summary));
        compressed.add(new AssistantMessage("Understood. I'll continue based on this context."));
        compressed.addAll(recentMessages);

        log.info("Compressed {} messages to {} messages (summary + {} recent)",
                messages.size(), compressed.size(), keepRecent);

        return compressed;
    }

    /**
     * 用 LLM 生成对话摘要
     */
    private String summarize(List<Message> messages) {
        try {
            // 构建摘要请求
            StringBuilder conversationText = new StringBuilder();
            for (Message msg : messages) {
                String role;
                if (msg instanceof UserMessage) {
                    role = "User";
                } else if (msg instanceof AssistantMessage) {
                    role = "Assistant";
                } else if (msg instanceof ToolResponseMessage) {
                    role = "Tool Result";
                } else if (msg instanceof SystemMessage) {
                    role = "System";
                } else {
                    role = "Unknown";
                }
                String text = msg.getText();
                if (text != null && !text.isBlank()) {
                    // 截断过长的工具结果
                    if (text.length() > 500) {
                        text = text.substring(0, 500) + "...";
                    }
                    conversationText.append(role).append(": ").append(text).append("\n");
                }
            }

            String promptText = """
                    Please summarize the following conversation in 3-5 sentences, preserving key context,
                    decisions made, and any important technical details. Focus on information that would be
                    useful for continuing the conversation.

                    Conversation:
                    %s
                    """.formatted(conversationText);

            Prompt prompt = new Prompt(List.of(new UserMessage(promptText)));
            var response = chatModel.call(prompt);
            String result = response.getResult().getOutput().getText();

            return result != null ? result.trim() : null;

        } catch (Exception e) {
            log.error("Failed to generate summary: {}", e.getMessage());
            return null;
        }
    }
}
