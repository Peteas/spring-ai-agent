package com.sakura.spring.ai.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    // 返回 JSON Schema 格式的参数定义
    Map<String, Object> parameters();

    ToolResult execute(Map<String, Object> args);

    record ToolResult(
            @JsonProperty("output") String output,
            @JsonProperty("is_error") boolean isError
    ) {
        public static ToolResult success(String output) {
            return new ToolResult(output, false);
        }

        public static ToolResult error(String message) {
            return new ToolResult(message, true);
        }
    }
}
