package com.sakura.spring.ai.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class FileTool implements Tool {

    private static final Path WORKING_DIR = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

    @Override
    public String name() {
        return "file_operations";
    }

    @Override
    public String description() {
        return "File operations tool. Supports: read_file (read file content with optional line range), write_file (create or overwrite file), edit_file (precise string replacement), list_directory (list directory contents). Use the 'action' parameter to specify which operation to perform.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "description", "The file operation to perform: read_file, write_file, edit_file, list_directory",
                                "enum", List.of("read_file", "write_file", "edit_file", "list_directory")
                        ),
                        "path", Map.of(
                                "type", "string",
                                "description", "The file or directory path (relative to working directory or absolute)"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "Content to write (for write_file) or new content for replacement (for edit_file)"
                        ),
                        "old_string", Map.of(
                                "type", "string",
                                "description", "The exact string to find and replace (for edit_file)"
                        ),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Line number to start reading from (0-based, for read_file)"
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Maximum number of lines to read (for read_file)"
                        )
                ),
                "required", List.of("action", "path")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        String path = (String) args.get("path");

        if (path == null || path.isBlank()) {
            return ToolResult.error("Path is required");
        }

        Path filePath = Paths.get(path).normalize();

        // 安全校验：确保路径在工作目录内，防止路径穿越
        Path resolvedPath = WORKING_DIR.resolve(filePath).normalize();
        if (!resolvedPath.startsWith(WORKING_DIR)) {
            return ToolResult.error("Access denied: path outside working directory");
        }

        return switch (action) {
            case "read_file" -> readFile(resolvedPath, args);
            case "write_file" -> writeFile(resolvedPath, args);
            case "edit_file" -> editFile(resolvedPath, args);
            case "list_directory" -> listDirectory(resolvedPath);
            default -> ToolResult.error("Unknown action: " + action);
        };
    }

    private ToolResult readFile(Path path, Map<String, Object> args) {
        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("Not a regular file: " + path);
        }

        try {
            List<String> lines = Files.readAllLines(path);
            int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 0;
            int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : lines.size();

            offset = Math.max(0, Math.min(offset, lines.size()));
            int end = Math.min(offset + limit, lines.size());

            StringBuilder sb = new StringBuilder();
            for (int i = offset; i < end; i++) {
                sb.append(String.format("%d\t%s%n", i + 1, lines.get(i)));
            }

            if (sb.isEmpty()) {
                return ToolResult.success("(empty file)");
            }
            return ToolResult.success(sb.toString());
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    private ToolResult writeFile(Path path, Map<String, Object> args) {
        String content = (String) args.get("content");
        if (content == null) {
            return ToolResult.error("Content is required for write_file");
        }

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return ToolResult.success("File written successfully: " + path);
        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    private ToolResult editFile(Path path, Map<String, Object> args) {
        if (!Files.exists(path)) {
            return ToolResult.error("File not found: " + path);
        }

        String oldString = (String) args.get("old_string");
        String newString = (String) args.get("content");

        if (oldString == null || oldString.isBlank()) {
            return ToolResult.error("old_string is required for edit_file");
        }

        try {
            String fileContent = Files.readString(path);
            int count = countOccurrences(fileContent, oldString);

            if (count == 0) {
                return ToolResult.error("old_string not found in file");
            }
            if (count > 1) {
                return ToolResult.error("old_string found " + count + " times. Provide more context to make it unique.");
            }

            String updated = fileContent.replace(oldString, newString == null ? "" : newString);
            Files.writeString(path, updated);
            return ToolResult.success("File edited successfully: " + path);
        } catch (IOException e) {
            return ToolResult.error("Failed to edit file: " + e.getMessage());
        }
    }

    private ToolResult listDirectory(Path path) {
        if (!Files.exists(path)) {
            return ToolResult.error("Directory not found: " + path);
        }
        if (!Files.isDirectory(path)) {
            return ToolResult.error("Not a directory: " + path);
        }

        try (Stream<Path> stream = Files.list(path)) {
            String listing = stream
                    .sorted()
                    .map(p -> {
                        String prefix = Files.isDirectory(p) ? "[DIR] " : "[FILE] ";
                        return prefix + p.getFileName();
                    })
                    .collect(Collectors.joining("\n"));

            if (listing.isEmpty()) {
                return ToolResult.success("(empty directory)");
            }
            return ToolResult.success(listing);
        } catch (IOException e) {
            return ToolResult.error("Failed to list directory: " + e.getMessage());
        }
    }

    private int countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }
}
