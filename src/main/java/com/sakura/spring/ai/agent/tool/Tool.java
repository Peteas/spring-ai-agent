package com.sakura.spring.ai.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    // 返回 JSON Schema 格式的参数定义
    Map<String, Object> parameters();

    ToolResult execute(Map<String, Object> args);

    /**
     * 工具权限等级
     */
    enum PermissionLevel {
        /** 只读，无副作用 */
        READ,
        /** 有写入副作用但可控 */
        WRITE,
        /** 不可逆/高危操作 */
        DESTRUCTIVE
    }

    /**
     * 声明工具的默认权限等级
     */
    default PermissionLevel permissionLevel() { return PermissionLevel.READ; }

    /**
     * 根据具体参数动态判断权限等级（默认使用静态权限）
     */
    default PermissionLevel permissionLevel(Map<String, Object> args) {
        return permissionLevel();
    }

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
