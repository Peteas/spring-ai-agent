package com.sakura.spring.ai.agent.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class JwtAuthenticationFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(JwtService jwtService, TokenBlacklistService tokenBlacklistService) {
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                if (jwtService.isTokenValid(token) && jwtService.isAccessToken(token)) {
                    if (tokenBlacklistService.isBlacklisted(token)) {
                        log.debug("Token is blacklisted, proceeding as unauthenticated");
                        return chain.filter(exchange);
                    }

                    io.jsonwebtoken.Claims claims = jwtService.parseToken(token);
                    Long userId = Long.parseLong(claims.getSubject());
                    String username = claims.get("username", String.class);

                    JwtUserDetails userDetails = new JwtUserDetails(userId, username);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null, List.of());

                    SecurityContextImpl securityContext = new SecurityContextImpl(authentication);

                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)));
                }
            } catch (Exception e) {
                log.debug("Invalid token: {}", e.getMessage());
            }
        }

        return chain.filter(exchange);
    }
}
