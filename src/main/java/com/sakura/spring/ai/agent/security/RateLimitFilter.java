package com.sakura.spring.ai.agent.security;

import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

@Component
public class RateLimitFilter implements WebFilter {

    private static final String RATE_LIMIT_PREFIX = "mimo:ratelimit:";
    private static final RateLimitConfig CHAT_LIMIT = new RateLimitConfig(20, Duration.ofMinutes(1));

    private static final Map<String, RateLimitConfig> ENDPOINT_LIMITS = Map.of(
            "/api/auth/login", new RateLimitConfig(5, Duration.ofMinutes(1)),
            "/api/auth/register", new RateLimitConfig(3, Duration.ofMinutes(1)),
            "/api/auth/refresh", new RateLimitConfig(10, Duration.ofMinutes(1)),
            "/api/chat", CHAT_LIMIT
    );

    private static final DefaultRedisScript<Long> INCR_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('INCR', KEYS[1]) " +
            "if v == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return v",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final JwtService jwtService;

    @Autowired
    public RateLimitFilter(StringRedisTemplate redisTemplate, JwtService jwtService) {
        this.redisTemplate = redisTemplate;
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        RateLimitConfig config = ENDPOINT_LIMITS.get(path);

        if (config == null) {
            return chain.filter(exchange);
        }

        if (redisTemplate == null) {
            return chain.filter(exchange);
        }

        String rateLimitKey = buildRateLimitKey(exchange, path);

        return Mono.fromCallable(() -> {
            try {
                return redisTemplate.execute(
                        INCR_EXPIRE_SCRIPT,
                        Collections.singletonList(rateLimitKey),
                        String.valueOf(config.window().getSeconds())
                );
            } catch (Exception e) {
                return null;
            }
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMap(count -> {
            if (count != null && count > config.maxRequests()) {
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"error\":\"Too many requests. Please try again later.\"}";
                DataBuffer buffer = exchange.getResponse().bufferFactory()
                        .wrap(body.getBytes(StandardCharsets.UTF_8));
                return exchange.getResponse().writeWith(Mono.just(buffer));
            }
            return chain.filter(exchange);
        });
    }

    private String buildRateLimitKey(ServerWebExchange exchange, String path) {
        if ("/api/chat".equals(path)) {
            String userId = extractUserId(exchange);
            if (userId != null) {
                return RATE_LIMIT_PREFIX + path + ":user:" + userId;
            }
        }
        return RATE_LIMIT_PREFIX + path + ":ip:" + getClientIp(exchange);
    }

    private String extractUserId(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = authHeader.substring(7);
            if (!jwtService.isTokenValid(token) || !jwtService.isAccessToken(token)) {
                return null;
            }
            Claims claims = jwtService.parseToken(token);
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    private String getClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        if (exchange.getRequest().getRemoteAddress() != null) {
            return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private record RateLimitConfig(int maxRequests, Duration window) {}
}
