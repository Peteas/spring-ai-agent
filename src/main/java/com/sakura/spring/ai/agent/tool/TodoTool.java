package com.sakura.spring.ai.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class TodoTool implements Tool {

    private final List<TodoItem> todos = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public String description() {
        return "Task management tool. Track and manage work items. Supports: create (add new task), update (change task status), list (show all tasks), delete (remove a task). Status values: pending, in_progress, completed.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "description", "Todo action: create, update, list, delete",
                                "enum", List.of("create", "update", "list", "delete")
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "Task description (for create)"
                        ),
                        "id", Map.of(
                                "type", "integer",
                                "description", "Task ID (for update/delete)"
                        ),
                        "status", Map.of(
                                "type", "string",
                                "description", "Task status (for update): pending, in_progress, completed",
                                "enum", List.of("pending", "in_progress", "completed")
                        )
                ),
                "required", List.of("action")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String action = (String) args.get("action");

        return switch (action) {
            case "create" -> createTodo(args);
            case "update" -> updateTodo(args);
            case "list" -> listTodos();
            case "delete" -> deleteTodo(args);
            default -> ToolResult.error("Unknown action: " + action);
        };
    }

    private ToolResult createTodo(Map<String, Object> args) {
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            return ToolResult.error("Task content is required");
        }

        int id = todos.size() + 1;
        TodoItem item = new TodoItem(id, content, "pending", "activeForm");
        todos.add(item);
        return ToolResult.success("Task created: #" + id + " - " + content);
    }

    private ToolResult updateTodo(Map<String, Object> args) {
        if (!args.containsKey("id")) {
            return ToolResult.error("Task ID is required");
        }
        int id = ((Number) args.get("id")).intValue();
        String status = (String) args.get("status");

        for (TodoItem todo : todos) {
            if (todo.id() == id) {
                if (status != null) {
                    int idx = todos.indexOf(todo);
                    todos.set(idx, new TodoItem(todo.id(), todo.content(), status, todo.activeForm()));
                }
                return ToolResult.success("Task #" + id + " updated to status: " + (status != null ? status : todo.status()));
            }
        }
        return ToolResult.error("Task #" + id + " not found");
    }

    private ToolResult listTodos() {
        if (todos.isEmpty()) {
            return ToolResult.success("No tasks found.");
        }

        StringBuilder sb = new StringBuilder("Tasks:\n");
        for (TodoItem todo : todos) {
            String statusIcon = switch (todo.status()) {
                case "completed" -> "[x]";
                case "in_progress" -> "[~]";
                default -> "[ ]";
            };
            sb.append(String.format("  %s #%d: %s (%s)%n", statusIcon, todo.id(), todo.content(), todo.status()));
        }
        return ToolResult.success(sb.toString());
    }

    private ToolResult deleteTodo(Map<String, Object> args) {
        if (!args.containsKey("id")) {
            return ToolResult.error("Task ID is required");
        }
        int id = ((Number) args.get("id")).intValue();

        boolean removed = todos.removeIf(t -> t.id() == id);
        if (removed) {
            return ToolResult.success("Task #" + id + " deleted");
        }
        return ToolResult.error("Task #" + id + " not found");
    }

    public record TodoItem(int id, String content, String status, String activeForm) {}
}
