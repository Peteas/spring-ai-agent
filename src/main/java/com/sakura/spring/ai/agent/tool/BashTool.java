package com.sakura.spring.ai.agent.tool;

import com.sakura.spring.ai.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class BashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private final AgentProperties agentProperties;

    public BashTool(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    private int commandTimeout() {
        return agentProperties.getCommandTimeout();
    }

    private Path workingDir() {
        return agentProperties.getWorkingDirPath();
    }

    // ── 高危命令模式 ──────────────────────────────────────────────────

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            // 破坏性文件操作
            Pattern.compile("rm\\s+(-[a-zA-Z]*f[a-zA-Z]*|[a-zA-Z]*f[a-zA-Z]*-)?\\s*/"),       // rm / 或 rm -rf /
            Pattern.compile("rm\\s+(-[a-zA-Z]*)?\\s+\\*"),                                       // rm *
            Pattern.compile("mkfs"),                                                               // mkfs
            Pattern.compile("dd\\s+if="),                                                          // dd if=
            Pattern.compile(":\\(\\)\\s*\\{"),                                                     // fork bomb

            // 下载并执行（绕过 curl|bash 的变体）
            Pattern.compile("(curl|wget)\\s+.*\\|\\s*(bash|sh|python|perl|ruby|node)"),            // curl|bash 等
            Pattern.compile("(curl|wget)\\s+.*&&\\s*(bash|sh)\\s"),                                // curl && bash file
            Pattern.compile("(curl|wget)\\s+.*-o\\s+.*&&\\s*(bash|sh)"),                           // curl -o x && bash x

            // 子 shell / eval
            Pattern.compile("eval\\s+"),                                                           // eval

            // 解释器内联代码执行
            Pattern.compile("python[23]?\\s+-c\\s+.*(os\\.system|subprocess|exec\\s*\\()"),        // python -c exec
            Pattern.compile("perl\\s+-e\\s+.*system"),                                             // perl -e system
            Pattern.compile("ruby\\s+-e\\s+.*system"),                                             // ruby -e system
            Pattern.compile("node\\s+-e\\s+.*(exec|spawn)"),                                       // node -e exec

            // 管道到解释器
            Pattern.compile("\\|\\s*(bash|sh|python|perl|ruby|node)\\b"),                          // | bash/python/perl

            // 磁盘/设备写入
            Pattern.compile(">/dev/sd[a-z]"),                                                      // 写入磁盘
            Pattern.compile(">\\s*/etc/"),                                                           // 写入 /etc

            // 危险权限
            Pattern.compile("chmod\\s+(777|666|a\\+w)"),                                           // chmod 777 等

            // 反弹 shell
            Pattern.compile("nc\\s+.*-e"),                                                         // netcat reverse shell
            Pattern.compile("ncat\\s+.*-e"),                                                       // ncat reverse shell
            Pattern.compile("bash\\s+-i\\s+.*>&\\s*/dev/tcp"),                                     // bash reverse shell

            // 移动/复制到根目录
            Pattern.compile("mv\\s+.*\\s+/\\s"),                                                    // 移动到根目录
            Pattern.compile("cp\\s+.*\\s+/\\s"),                                                     // 复制到根目录

            // 命令替换（排除算术表达式 $((...)) ）
            // $(command) — 非算术表达式的命令替换
            Pattern.compile("\\$\\((?!\\()[^)]*\\)"),                                               // $(cmd) 但不是 $((expr))
            // `command` — 反引号命令替换
            Pattern.compile("`[^`]+`")                                                              // `command`
    );

    // 算术表达式 $((...)) 的精确匹配，用于排除误杀
    private static final Pattern ARITHMETIC_EXPANSION = Pattern.compile("\\$\\(\\([^)]*\\)\\)");

    // ── 命令策略：高危命令需要更严格的参数校验 ──────────────────────────

    private enum RiskLevel { SAFE, CAUTION, HIGH_RISK }

    private static final Map<String, RiskLevel> COMMAND_RISK_LEVELS = Map.ofEntries(
            // SAFE — 只读/低风险
            Map.entry("ls", RiskLevel.SAFE),
            Map.entry("pwd", RiskLevel.SAFE),
            Map.entry("echo", RiskLevel.SAFE),
            Map.entry("cat", RiskLevel.SAFE),
            Map.entry("head", RiskLevel.SAFE),
            Map.entry("tail", RiskLevel.SAFE),
            Map.entry("grep", RiskLevel.SAFE),
            Map.entry("find", RiskLevel.SAFE),
            Map.entry("wc", RiskLevel.SAFE),
            Map.entry("sort", RiskLevel.SAFE),
            Map.entry("uniq", RiskLevel.SAFE),
            Map.entry("diff", RiskLevel.SAFE),
            Map.entry("file", RiskLevel.SAFE),
            Map.entry("stat", RiskLevel.SAFE),
            Map.entry("du", RiskLevel.SAFE),
            Map.entry("df", RiskLevel.SAFE),
            Map.entry("which", RiskLevel.SAFE),
            Map.entry("whereis", RiskLevel.SAFE),
            Map.entry("type", RiskLevel.SAFE),
            Map.entry("man", RiskLevel.SAFE),
            Map.entry("help", RiskLevel.SAFE),
            Map.entry("info", RiskLevel.SAFE),
            Map.entry("date", RiskLevel.SAFE),
            Map.entry("cal", RiskLevel.SAFE),
            Map.entry("bc", RiskLevel.SAFE),
            Map.entry("git", RiskLevel.SAFE),
            Map.entry("mvn", RiskLevel.SAFE),
            Map.entry("gradle", RiskLevel.SAFE),
            Map.entry("java", RiskLevel.SAFE),
            Map.entry("javac", RiskLevel.SAFE),
            Map.entry("npm", RiskLevel.SAFE),
            Map.entry("yarn", RiskLevel.SAFE),
            Map.entry("pnpm", RiskLevel.SAFE),
            Map.entry("node", RiskLevel.SAFE),
            Map.entry("npx", RiskLevel.SAFE),
            Map.entry("pip", RiskLevel.SAFE),
            Map.entry("pip3", RiskLevel.SAFE),
            Map.entry("tar", RiskLevel.SAFE),
            Map.entry("zip", RiskLevel.SAFE),
            Map.entry("unzip", RiskLevel.SAFE),
            Map.entry("gzip", RiskLevel.SAFE),
            Map.entry("gunzip", RiskLevel.SAFE),
            Map.entry("env", RiskLevel.SAFE),
            Map.entry("ps", RiskLevel.SAFE),
            Map.entry("top", RiskLevel.SAFE),

            // CAUTION — 有写入副作用但可控
            Map.entry("mkdir", RiskLevel.CAUTION),
            Map.entry("touch", RiskLevel.CAUTION),
            Map.entry("tee", RiskLevel.CAUTION),
            Map.entry("sed", RiskLevel.CAUTION),
            Map.entry("awk", RiskLevel.CAUTION),
            Map.entry("tr", RiskLevel.CAUTION),
            Map.entry("cut", RiskLevel.CAUTION),
            Map.entry("paste", RiskLevel.CAUTION),
            Map.entry("xargs", RiskLevel.CAUTION),
            Map.entry("python", RiskLevel.CAUTION),
            Map.entry("python3", RiskLevel.CAUTION),
            Map.entry("docker", RiskLevel.CAUTION),
            Map.entry("docker-compose", RiskLevel.CAUTION),
            Map.entry("make", RiskLevel.CAUTION),
            Map.entry("cmake", RiskLevel.CAUTION),
            Map.entry("gcc", RiskLevel.CAUTION),
            Map.entry("g++", RiskLevel.CAUTION),
            Map.entry("export", RiskLevel.CAUTION),
            Map.entry("set", RiskLevel.CAUTION),
            Map.entry("unset", RiskLevel.CAUTION),
            Map.entry("curl", RiskLevel.CAUTION),
            Map.entry("wget", RiskLevel.CAUTION),
            Map.entry("rsync", RiskLevel.CAUTION),

            // HIGH_RISK — 不可逆或高危操作
            Map.entry("rm", RiskLevel.HIGH_RISK),
            Map.entry("cp", RiskLevel.HIGH_RISK),
            Map.entry("mv", RiskLevel.HIGH_RISK),
            Map.entry("chmod", RiskLevel.HIGH_RISK),
            Map.entry("chown", RiskLevel.HIGH_RISK),
            Map.entry("kill", RiskLevel.HIGH_RISK),
            Map.entry("pkill", RiskLevel.HIGH_RISK),
            Map.entry("ssh", RiskLevel.HIGH_RISK),
            Map.entry("scp", RiskLevel.HIGH_RISK)
    );

    // 允许的安全命令前缀（白名单）
    private static final Set<String> SAFE_COMMAND_PREFIXES = COMMAND_RISK_LEVELS.keySet();

    // ── Tool 接口实现 ──────────────────────────────────────────────────

    @Override
    public String name() {
        return "execute_command";
    }

    @Override
    public String description() {
        return "Execute a shell command and return its output. Use for running tests, building projects, checking git status, installing packages, etc. Commands run in the working directory. Timeout: " + commandTimeout() + "s. WARNING: Destructive commands (rm, kill, etc.) require confirmation.";
    }

    @Override
    public PermissionLevel permissionLevel() {
        return PermissionLevel.DESTRUCTIVE;
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
                                "description", "Command timeout in seconds (default: " + commandTimeout() + ")"
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
                : commandTimeout();

        // 预处理：标准化空格，防止 "rm  -rf  /" 等绕过
        String normalizedCommand = command.replaceAll("\\s+", " ").trim();

        // 安全校验
        String blockReason = validateCommand(normalizedCommand);
        if (blockReason != null) {
            log.warn("BLOCKED command: {} | reason: {}", normalizedCommand, blockReason);
            return ToolResult.error(blockReason);
        }

        // 审计日志 — 记录所有 HIGH_RISK 命令
        String primaryCmd = extractFirstCommand(normalizedCommand);
        RiskLevel risk = COMMAND_RISK_LEVELS.getOrDefault(primaryCmd, RiskLevel.CAUTION);
        if (risk == RiskLevel.HIGH_RISK) {
            log.warn("HIGH_RISK command executed: {} | dir: {}", normalizedCommand, workingDir());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder();
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }

            pb.directory(workingDir().toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 30000) {
                        output.append("\n... (output truncated)");
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                        return ToolResult.success(output.toString());
                    }
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
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

    // ── 安全校验 ──────────────────────────────────────────────────

    /**
     * 综合安全校验，返回 null 表示通过，返回字符串表示拦截原因
     */
    private String validateCommand(String normalizedCommand) {
        String lowerCmd = normalizedCommand.toLowerCase();

        // 1. 检测子 shell 包装
        if (lowerCmd.startsWith("bash -c") || lowerCmd.startsWith("sh -c") ||
            lowerCmd.startsWith("bash -ic") || lowerCmd.startsWith("sh -ic")) {
            return "Subshell execution is not allowed for security reasons";
        }

        // 2. 检查高危命令模式（排除算术表达式误杀）
        String withoutArithmetic = ARITHMETIC_EXPANSION.matcher(normalizedCommand).replaceAll("");
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(withoutArithmetic).find()) {
                return "Blocked dangerous command pattern: " + pattern.pattern();
            }
        }

        // 3. 按 shell 操作符分割，每个子命令独立验证白名单
        //    分割符：&&, ||, ;, |（但不排除 $((...)) 中的 |）
        String[] subCommands = splitShellCommands(normalizedCommand);
        for (String sub : subCommands) {
            String subCmd = sub.trim();
            if (subCmd.isEmpty()) continue;
            String cmd = extractFirstCommand(subCmd);
            if (cmd != null && !SAFE_COMMAND_PREFIXES.contains(cmd)) {
                return "Command not allowed: " + cmd + ". Allowed commands: " + SAFE_COMMAND_PREFIXES;
            }
        }

        return null; // 校验通过
    }

    /**
     * 按 shell 操作符分割命令，但跳过算术表达式中的操作符
     */
    private String[] splitShellCommands(String command) {
        // 先用占位符替换算术表达式，再按操作符分割，最后还原
        List<String> arithmeticParts = new ArrayList<>();
        String placeholder = " ARITH_%d ";
        String sanitized = command;

        // 提取 $((...)) 部分
        var matcher = ARITHMETIC_EXPANSION.matcher(sanitized);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (matcher.find()) {
            arithmeticParts.add(matcher.group());
            sb.append(sanitized, idx, matcher.start());
            sb.append(String.format(placeholder, arithmeticParts.size() - 1));
            idx = matcher.end();
        }
        sb.append(sanitized.substring(idx));
        sanitized = sb.toString();

        // 按操作符分割
        String[] parts = sanitized.split("\\s*(?:&&|\\|\\||[;|])\\s*");

        // 还原算术表达式
        for (int i = 0; i < parts.length; i++) {
            for (int j = 0; j < arithmeticParts.size(); j++) {
                parts[i] = parts[i].replace(String.format(placeholder, j), arithmeticParts.get(j));
            }
        }
        return parts;
    }

    /**
     * 提取命令的第一个单词（主命令）
     * 绝对路径也纳入检测（如 /bin/rm → rm）
     */
    private String extractFirstCommand(String command) {
        String trimmed = command.trim();
        // 跳过环境变量设置（如 VAR=value command）
        if (trimmed.matches("^[a-zA-Z_][a-zA-Z0-9_]*=.*")) {
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length > 1) {
                trimmed = parts[1];
            }
        }
        // 跳过 sudo 前缀
        if (trimmed.startsWith("sudo ")) {
            trimmed = trimmed.substring(5).trim();
        }
        // 提取第一个单词
        String[] parts = trimmed.split("\\s+");
        String cmd = parts[0];
        // 处理绝对路径形式的命令（如 /usr/bin/python → python, /bin/rm → rm）
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(cmd.lastIndexOf("/") + 1);
        }
        return cmd;
    }
}
