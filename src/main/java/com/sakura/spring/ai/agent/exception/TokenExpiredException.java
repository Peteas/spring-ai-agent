package com.sakura.spring.ai.agent.exception;

public class TokenExpiredException extends BusinessException {
    public TokenExpiredException() {
        super("Invalid or expired token");
    }
}
