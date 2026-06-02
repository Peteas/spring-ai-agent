package com.sakura.spring.ai.agent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    @Value("${mimo.jwt.secret}")
    private String secret;

    @Value("${mimo.jwt.expiration}")
    private long expiration;

    @Value("${mimo.jwt.refresh-expiration}")
    private long refreshExpiration;

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET environment variable is required");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes (256 bits)");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long userId, String username) {
        return buildToken(userId, username, TOKEN_TYPE_ACCESS, expiration);
    }

    public String generateRefreshToken(Long userId, String username) {
        return buildToken(userId, username, TOKEN_TYPE_REFRESH, refreshExpiration);
    }

    private String buildToken(Long userId, String username, String tokenType, long expirationTime) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationTime);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("token_type", tokenType)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    public String getTokenType(String token) {
        Claims claims = parseToken(token);
        return claims.get("token_type", String.class);
    }

    public boolean isAccessToken(String token) {
        return TOKEN_TYPE_ACCESS.equals(getTokenType(token));
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("username", String.class);
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = parseToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }
}
