package com.sakura.spring.ai.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sakura.spring.ai.agent.exception.DuplicateUserException;
import com.sakura.spring.ai.agent.exception.InvalidCredentialsException;
import com.sakura.spring.ai.agent.exception.TokenExpiredException;
import com.sakura.spring.ai.agent.mapper.UserMapper;
import com.sakura.spring.ai.agent.mapper.UserSessionMapper;
import com.sakura.spring.ai.agent.model.User;
import com.sakura.spring.ai.agent.model.UserSession;
import com.sakura.spring.ai.agent.security.JwtService;
import com.sakura.spring.ai.agent.security.TokenBlacklistService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final UserSessionMapper userSessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    public UserService(UserMapper userMapper, UserSessionMapper userSessionMapper,
                       PasswordEncoder passwordEncoder, JwtService jwtService,
                       TokenBlacklistService tokenBlacklistService) {
        this.userMapper = userMapper;
        this.userSessionMapper = userSessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Transactional
    public Map<String, Object> register(String username, String password, String email) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new DuplicateUserException();
        }

        // 创建用户
        User user = new User(username, passwordEncoder.encode(password), email);
        userMapper.insert(user);

        // 生成 token
        String accessToken = jwtService.generateToken(user.getId(), user.getUsername());
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getUsername());

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("accessToken", accessToken);
        result.put("refreshToken", refreshToken);
        return result;
    }

    @Transactional
    public Map<String, Object> login(String username, String password) {
        // 查找用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User user = userMapper.selectOne(wrapper);

        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // 生成 token
        String accessToken = jwtService.generateToken(user.getId(), user.getUsername());
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getUsername());

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("accessToken", accessToken);
        result.put("refreshToken", refreshToken);
        return result;
    }

    // 旧 refresh token 作废，生成新 token 对
    @Transactional
    public Map<String, Object> refreshToken(String refreshToken) {
        if (!jwtService.isTokenValid(refreshToken)) {
            throw new TokenExpiredException();
        }
        if (!JwtService.TOKEN_TYPE_REFRESH.equals(jwtService.getTokenType(refreshToken))) {
            throw new TokenExpiredException();
        }
        // 检查 token 是否在黑名单中
        if (tokenBlacklistService.isBlacklisted(refreshToken)) {
            throw new TokenExpiredException();
        }

        Long userId = jwtService.getUserIdFromToken(refreshToken);
        String username = jwtService.getUsernameFromToken(refreshToken);

        tokenBlacklistService.blacklist(refreshToken, 7, TimeUnit.DAYS);

        // 生成新的 access + refresh token 对
        String newAccessToken = jwtService.generateToken(userId, username);
        String newRefreshToken = jwtService.generateRefreshToken(userId, username);

        Map<String, Object> result = new HashMap<>();
        result.put("accessToken", newAccessToken);
        result.put("refreshToken", newRefreshToken);
        return result;
    }

    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null) {
            tokenBlacklistService.blacklist(accessToken, 24, TimeUnit.HOURS);
        }
        if (refreshToken != null) {
            tokenBlacklistService.blacklist(refreshToken, 7, TimeUnit.DAYS);
        }
    }

    public boolean isSessionOwnedByAnyone(String sessionId) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getSessionId, sessionId);
        return userSessionMapper.selectCount(wrapper) > 0;
    }

    public User getUserById(Long userId) {
        return userMapper.selectById(userId);
    }

    // 忽略重复关联
    public void associateSession(Long userId, String sessionId) {
        try {
            UserSession userSession = new UserSession(userId, sessionId);
            userSessionMapper.insert(userSession);
        } catch (DuplicateKeyException e) {
            // Already associated — ignore
        }
    }

    public List<String> getUserSessions(Long userId) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getUserId, userId)
               .orderByDesc(UserSession::getCreatedAt);
        return userSessionMapper.selectList(wrapper)
                .stream()
                .map(UserSession::getSessionId)
                .toList();
    }

    public boolean isSessionOwner(Long userId, String sessionId) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getUserId, userId)
               .eq(UserSession::getSessionId, sessionId);
        return userSessionMapper.selectCount(wrapper) > 0;
    }

    public void removeSession(Long userId, String sessionId) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getUserId, userId)
               .eq(UserSession::getSessionId, sessionId);
        userSessionMapper.delete(wrapper);
    }
}
