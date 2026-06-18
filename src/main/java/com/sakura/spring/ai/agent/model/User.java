package com.sakura.spring.ai.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;             // 自增主键
    private String username;     // 登录用的用户名，不能重复
    private String passwordHash; // 密码不存明文，只存 bcrypt hash
    private String email;        // 邮箱，选填
    private LocalDateTime createdAt; // 注册时间，创建时自动填

    public User() {}

    public User(String username, String passwordHash, String email) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.email = email;
        this.createdAt = LocalDateTime.now();
    }
}
