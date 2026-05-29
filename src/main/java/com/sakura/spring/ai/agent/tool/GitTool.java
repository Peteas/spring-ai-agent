package com.sakura.spring.ai.agent.tool;

import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;

@Component
public class GitTool implements Tool {

    @Override
    public String name() {
        return "git";
    }

    @Override
    public String description() {
        return "Git operations tool. Supports: status (show working tree status), diff (show changes), log (show commit history), commit (create a commit), add (stage files), branch (list/create branches). Use 'action' to specify operation.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "description", "Git action: status, diff, log, commit, add, branch",
                                "enum", List.of("status", "diff", "log", "commit", "add", "branch")
                        ),
                        "message", Map.of(
                                "type", "string",
                                "description", "Commit message (required for commit action)"
                        ),
                        "files", Map.of(
                                "type", "array",
                                "description", "File paths to operate on (for add/commit)",
                                "items", Map.of("type", "string")
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Number of log entries to show (default: 10)"
                        )
                ),
                "required", List.of("action")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String action = (String) args.get("action");

        return switch (action) {
            case "status" -> runGit("status");
            case "diff" -> runGit("diff");
            case "log" -> {
                int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 10;
                yield runGit("log", "--oneline", "-n", String.valueOf(limit));
            }
            case "add" -> {
                List<String> files = (List<String>) args.get("files");
                if (files == null || files.isEmpty()) {
                    yield runGit("add", ".");
                }
                String[] fileArgs = new String[files.size() + 1];
                fileArgs[0] = "add";
                for (int i = 0; i < files.size(); i++) {
                    fileArgs[i + 1] = files.get(i);
                }
                yield runGit(fileArgs);
            }
            case "commit" -> {
                String message = (String) args.get("message");
                if (message == null || message.isBlank()) {
                    yield ToolResult.error("Commit message is required");
                }
                yield runGit("commit", "-m", message);
            }
            case "branch" -> runGit("branch", "-a");
            default -> ToolResult.error("Unknown git action: " + action);
        };
    }

    private ToolResult runGit(String... gitArgs) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(gitArgs));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            process.waitFor();
            int exitCode = process.exitValue();

            String result = output.toString().isEmpty() ? "(no output)" : output.toString();
            if (exitCode != 0) {
                return ToolResult.error("Git command failed (exit " + exitCode + "):\n" + result);
            }
            return ToolResult.success(result);

        } catch (Exception e) {
            return ToolResult.error("Failed to run git: " + e.getMessage());
        }
    }
}
