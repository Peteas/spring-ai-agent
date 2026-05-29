package com.sakura.spring.ai.agent.security;

public class JwtUserDetails {

    private final Long userId;
    private final String username;

    public JwtUserDetails(Long userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public Long getUserId() { return userId; }
    public String getUsername() { return username; }
}
