package com.sakura.spring.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_sessions")
public class UserSession {

    @TableId(type = IdType.AUTO)
    private Long id;             // 自增主键
    private Long userId;         // 关联的用户
    private String sessionId;    // 前端生成的会话ID
    private LocalDateTime createdAt; // 绑定时间

    public UserSession() {}

    public UserSession(Long userId, String sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.createdAt = LocalDateTime.now();
    }
}
