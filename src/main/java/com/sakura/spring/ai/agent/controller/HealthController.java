package com.sakura.spring.ai.agent.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;

    public HealthController(DataSource dataSource,
                            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "spring-ai-agent");
        body.put("timestamp", LocalDateTime.now().toString());

        boolean dbUp = checkDatabase();
        boolean redisUp = checkRedis();

        body.put("database", dbUp ? "UP" : "DOWN");
        body.put("redis", redisUp ? "UP" : (redisTemplate == null ? "NOT_CONFIGURED" : "DOWN"));
        body.put("status", dbUp ? "UP" : "DEGRADED");

        return ResponseEntity.ok(body);
    }

    private boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

	private boolean checkRedis() {
        if (redisTemplate == null) {
            return false;
        }
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return pong != null;
        } catch (Exception e) {
            return false;
        }
    }
}
