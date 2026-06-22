package com.sakura.spring.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_logs")
public class ChatLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;          // 谁发的（没登录就是 null）
    private String sessionId;     // 属于哪个会话
    private String userMessage;   // 用户说的话
    private String assistantMessage; // 模型回复的内容
    private Integer promptTokens;     // 输入消耗的 token
    private Integer completionTokens; // 输出消耗的 token
    private Integer totalTokens;      // 上面俩加起来
    private String toolsUsed;     // 用了哪些工具，json 数组存的
    private Integer toolCallCount;    // 工具调了几次
    private Integer roundCount;   // 跑了几轮（有工具调用时会多轮）
    private Long latencyMs;       // 这次对话总共耗时多久，毫秒
    private String model;         // 用的哪个模型
    private String error;         // 出错了就记下来，正常的话是 null
    private Integer userRating;   // 用户评分 1-5，nullable
    private Double qualityScore;  // 计算的质量分，nullable
    private LocalDateTime createdAt;  // 什么时候产生的

    public ChatLog() {}
}
