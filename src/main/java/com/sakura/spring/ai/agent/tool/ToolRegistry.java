package com.sakura.spring.ai.agent.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private final Map<String, MiMoToolCallback> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<Tool> toolBeans) {
        for (Tool tool : toolBeans) {
            tools.put(tool.name(), new MiMoToolCallback(tool));
        }
    }

    public Tool.ToolResult execute(String toolName, Map<String, Object> args) {
        MiMoToolCallback callback = tools.get(toolName);
        if (callback == null) {
            return Tool.ToolResult.error("Unknown tool: " + toolName);
        }
        try {
            Tool tool = callback.getTool();
            return tool.execute(args);
        } catch (Exception e) {
            return Tool.ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }

    public Collection<MiMoToolCallback> getAllCallbacks() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public List<ToolCallback> getToolCallbacks() {
        return new ArrayList<>(tools.values());
    }

    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    public MiMoToolCallback getCallback(String name) {
        return tools.get(name);
    }

    /**
     * 查询工具的权限等级（根据具体参数动态判断）
     */
    public Tool.PermissionLevel getPermissionLevel(String toolName, Map<String, Object> args) {
        MiMoToolCallback callback = tools.get(toolName);
        if (callback == null) {
            return Tool.PermissionLevel.DESTRUCTIVE; // 未知工具按最高权限
        }
        return callback.getTool().permissionLevel(args);
    }
}
