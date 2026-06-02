package com.sakura.spring.ai.agent.exception;

public class InvalidCredentialsException extends BusinessException {
    public InvalidCredentialsException() {
        super("Invalid username or password");
    }
}
