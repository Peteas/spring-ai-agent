package com.sakura.spring.ai.agent.exception;

public class DuplicateUserException extends BusinessException {
    public DuplicateUserException() {
        super("Username already exists");
    }
}
