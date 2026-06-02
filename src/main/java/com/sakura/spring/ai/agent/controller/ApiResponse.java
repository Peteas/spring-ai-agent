package com.sakura.spring.ai.agent.controller;

import java.util.Map;

public class ApiResponse {

    public static Map<String, Object> success(Object data) {
        return Map.of("code", 0, "data", data, "message", "ok");
    }

    public static Map<String, Object> success() {
        return Map.of("code", 0, "data", "", "message", "ok");
    }

    public static Map<String, Object> error(String message) {
        return Map.of("code", -1, "data", "", "message", message);
    }

    public static Map<String, Object> error(int code, String message) {
        return Map.of("code", code, "data", "", "message", message);
    }
}
