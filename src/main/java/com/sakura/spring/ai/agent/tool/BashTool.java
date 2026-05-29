package com.sakura.spring.ai.agent.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class BashTool implements Tool {

    @Value("${mimo.agent.command-timeout:120}")
    private int commandTimeout;

    // 高危命令模式 - 使用正则匹配
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("rm\\s+(-[a-zA-Z]*)?\\s*/"),           // rm / 或 rm -rf /
            Pattern.compile("rm\\s+(-[a-zA-Z]*)?\\s+\\*"),         // rm *
            Pattern.compile("mkfs"),                                 // mkfs
            Pattern.compile("dd\\s+if="),                            // dd if=
            Pattern.compile(":\\(\\)\\s*\\{"),                       // fork bomb
            Pattern.compile("curl\\s+.*\\|\\s*(bash|sh)"),           // curl|bash
            Pattern.compile("wget\\s+.*\\|\\s*(bash|sh)"),           // wget|sh
            Pattern.compile("eval\\s+"),                             // eval
            Pattern.compile("python[23]?\\s+-c\\s+.*os\\.system"),   // python -c os.system
            Pattern.compile("chmod\\s+777"),                         // chmod 777
            Pattern.compile("nc\\s+.*-e"),                           // netcat reverse shell
            Pattern.compile(">/dev/sd[a-z]"),                        // 写入磁盘
            Pattern.compile("mv\\s+.*\\s+/"),                        // 移动到根目录
            Pattern.compile("cp\\s+.*\\s+/")                         // 复制到根目录
    );

    // 允许的安全命令前缀（白名单）
    private static final Set<String> SAFE_COMMAND_PREFIXES = Set.of(
            "ls", "pwd", "echo", "cat", "head", "tail", "grep", "find", "wc",
            "sort", "uniq", "diff", "file", "stat", "du", "df",
            "git", "mvn", "gradle", "java", "javac",
            "npm", "yarn", "pnpm", "node", "npx",
            "python", "python3", "pip", "pip3",
            "docker", "docker-compose",
            "make", "cmake", "gcc", "g++",
            "curl", "wget",  // 允许下载，但拦截管道到 shell
            "mkdir", "touch", "rm", "cp", "mv", "chmod", "chown",
            "tar", "zip", "unzip", "gzip", "gunzip",
            "ssh", "scp", "rsync",
            "ps", "top", "kill", "pkill",
            "env", "export", "set", "unset",
            "date", "cal", "bc",
            "sed", "awk", "tr", "cut", "paste",
            "tee", "xargs",
            "which", "whereis", "type",
            "man", "help", "info"
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

        // 检查高危命令模式
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return ToolResult.error("Blocked dangerous command pattern: " + pattern.pattern());
            }
        }

        // 检查命令前缀是否在白名单中
        String firstCommand = extractFirstCommand(command);
        if (firstCommand != null && !SAFE_COMMAND_PREFIXES.contains(firstCommand)) {
            return ToolResult.error("Command not allowed: " + firstCommand + ". Allowed commands: " + SAFE_COMMAND_PREFIXES);
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

    /**
     * 提取命令的第一个单词（主命令）
     */
    private String extractFirstCommand(String command) {
        String trimmed = command.trim();
        // 跳过环境变量设置（如 VAR=value command）
        if (trimmed.matches("^[A-Z_][A-Z0-9_]*=.*")) {
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length > 1) {
                trimmed = parts[1];
            }
        }
        // 提取第一个单词
        String[] parts = trimmed.split("\\s+");
        String cmd = parts[0];
        // 处理路径形式的命令（如 /usr/bin/python）
        if (cmd.contains("/")) {
            cmd = cmd.substring(cmd.lastIndexOf("/") + 1);
        }
        return cmd;
    }
}
