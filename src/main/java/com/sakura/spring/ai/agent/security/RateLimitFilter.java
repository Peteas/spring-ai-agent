package com.sakura.spring.ai.agent.security;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Component
public class RateLimitFilter implements WebFilter {

    private static final String RATE_LIMIT_PREFIX = "mimo:ratelimit:";

    private static final Map<String, RateLimitConfig> ENDPOINT_LIMITS = Map.of(
            "/api/auth/login", new RateLimitConfig(5, Duration.ofMinutes(1)),
            "/api/auth/register", new RateLimitConfig(3, Duration.ofMinutes(1)),
            "/api/auth/refresh", new RateLimitConfig(10, Duration.ofMinutes(1))
    );

    private final StringRedisTemplate redisTemplate;

    public RateLimitFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        RateLimitConfig config = ENDPOINT_LIMITS.get(path);

        if (config == null) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange);
        String key = RATE_LIMIT_PREFIX + path + ":" + clientIp;

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, config.window());
            }

            if (count != null && count > config.maxRequests()) {
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                String body = "{\"error\":\"Too many requests. Please try again later.\"}";
                DataBuffer buffer = exchange.getResponse().bufferFactory()
                        .wrap(body.getBytes(StandardCharsets.UTF_8));
                return exchange.getResponse().writeWith(Mono.just(buffer));
            }
        } catch (Exception e) {
            // Redis 不可用时跳过限流
        }

        return chain.filter(exchange);
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
