package com.sakura.spring.ai.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sakura.spring.ai.agent.mapper.UserMapper;
import com.sakura.spring.ai.agent.mapper.UserSessionMapper;
import com.sakura.spring.ai.agent.model.User;
import com.sakura.spring.ai.agent.model.UserSession;
import com.sakura.spring.ai.agent.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final UserSessionMapper userSessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(UserMapper userMapper, UserSessionMapper userSessionMapper,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.userSessionMapper = userSessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Map<String, Object> register(String username, String password, String email) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("Username already exists");
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

    public Map<String, Object> login(String username, String password) {
        // 查找用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User user = userMapper.selectOne(wrapper);

        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new RuntimeException("Invalid username or password");
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

    public Map<String, Object> refreshToken(String refreshToken) {
        if (!jwtService.isTokenValid(refreshToken)) {
            throw new RuntimeException("Invalid refresh token");
        }

        Long userId = jwtService.getUserIdFromToken(refreshToken);
        String username = jwtService.getUsernameFromToken(refreshToken);

        String newAccessToken = jwtService.generateToken(userId, username);

        Map<String, Object> result = new HashMap<>();
        result.put("accessToken", newAccessToken);
        return result;
    }

    public User getUserById(Long userId) {
        return userMapper.selectById(userId);
    }

    public void associateSession(Long userId, String sessionId) {
        // 检查是否已关联
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getUserId, userId)
               .eq(UserSession::getSessionId, sessionId);
        if (userSessionMapper.selectCount(wrapper) == 0) {
            UserSession userSession = new UserSession(userId, sessionId);
            userSessionMapper.insert(userSession);
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
