package com.sakura.spring.ai.agent.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class BashTool implements Tool {

    @Value("${mimo.agent.command-timeout:120}")
    private int commandTimeout;

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "rm -rf /", "rm -rf /*", "mkfs", "dd if=", ":(){ :|:& };:"
    );

    @Override
    public String name() {
        return "execute_command";
    }

    @Override
    public String description() {
        return "Execute a shell command and return its output. Use for running tests, building projects, checking git status, installing packages, etc. Commands run in the working directory. Timeout: " + commandTimeout + "s.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of(
                                "type", "string",
                                "description", "The shell command to execute"
                        ),
                        "timeout", Map.of(
                                "type", "integer",
                                "description", "Command timeout in seconds (default: " + commandTimeout + ")"
                        )
                ),
                "required", List.of("command")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = (String) args.get("command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("Command is required");
        }

        int timeout = args.containsKey("timeout")
                ? ((Number) args.get("timeout")).intValue()
                : commandTimeout;

        for (String blocked : BLOCKED_COMMANDS) {
            if (command.contains(blocked)) {
                return ToolResult.error("Blocked dangerous command: " + blocked);
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder();
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }

            pb.directory(new File(System.getProperty("user.dir")));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 30000) {
                        output.append("\n... (output truncated)");
                        process.destroyForcibly();
                        return ToolResult.success(output.toString());
                    }
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Command timed out after " + timeout + " seconds.\nPartial output:\n" + output);
            }

            int exitCode = process.exitValue();
            String result = output.toString().isEmpty() ? "(no output)" : output.toString();

            if (exitCode != 0) {
                return ToolResult.error("Command exited with code " + exitCode + "\n" + result);
            }
            return ToolResult.success(result);

        } catch (Exception e) {
            return ToolResult.error("Failed to execute command: " + e.getMessage());
        }
    }
}
