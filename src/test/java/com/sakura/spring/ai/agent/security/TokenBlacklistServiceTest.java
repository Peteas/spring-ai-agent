package com.sakura.spring.ai.agent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TokenBlacklistServiceTest {

    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void setUp() {
        tokenBlacklistService = new TokenBlacklistService(null);
    }

    @Test
    void blacklistsTokenInMemory() {
        String token = "test-token-abc";
        tokenBlacklistService.blacklist(token, 1, TimeUnit.HOURS);
        assertTrue(tokenBlacklistService.isBlacklisted(token));
    }

    @Test
    void nonBlacklistedTokenReturnsFalse() {
        assertFalse(tokenBlacklistService.isBlacklisted("unknown-token"));
    }

    @Test
    void ignoresNullOrBlankTokens() {
        tokenBlacklistService.blacklist(null, 1, TimeUnit.HOURS);
        tokenBlacklistService.blacklist("", 1, TimeUnit.HOURS);
        assertFalse(tokenBlacklistService.isBlacklisted(null));
        assertFalse(tokenBlacklistService.isBlacklisted(""));
    }
}
