package com.sakura.spring.ai.agent.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);
    private static final String TOKEN_BLACKLIST_PREFIX = "mimo:token:blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, Long> memoryBlacklist = new ConcurrentHashMap<>();

    public TokenBlacklistService(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void blacklist(String token, long timeout, TimeUnit unit) {
        if (token == null || token.isBlank()) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + unit.toMillis(timeout);
        memoryBlacklist.put(token, expiresAt);
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(TOKEN_BLACKLIST_PREFIX + token, "1", timeout, unit);
            } catch (Exception e) {
                log.warn("Redis token blacklist failed, using in-memory fallback: {}", e.getMessage());
            }
        }
    }

    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long expiresAt = memoryBlacklist.get(token);
        if (expiresAt != null) {
            if (System.currentTimeMillis() < expiresAt) {
                return true;
            }
            memoryBlacklist.remove(token);
        }
        if (redisTemplate != null) {
            try {
                return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + token));
            } catch (Exception e) {
                log.warn("Redis blacklist check failed: {}", e.getMessage());
            }
        }
        return false;
    }
}
