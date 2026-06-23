package com.sakura.spring.ai.agent;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

public class SystemPrompt {

    // 缓存 system prompt，避免每次请求都重新构建
    private static final AtomicReference<String> cachedPrompt = new AtomicReference<>();
    private static volatile String lastGitBranch = null;
    private static volatile long lastBuildTime = 0;
    private static final long CACHE_TTL = 60000; // 1 minute

    public static String build(Path workingDir) {
        long now = System.currentTimeMillis();
        String gitBranch = getGitBranch(workingDir);
        if (cachedPrompt.get() != null
                && (now - lastBuildTime) < CACHE_TTL
                && gitBranch.equals(lastGitBranch)) {
            return cachedPrompt.get();
        }

        String os = System.getProperty("os.name") + " " + System.getProperty("os.arch");
        String javaVersion = System.getProperty("java.version");
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String prompt = """
                You are MiMo Code Agent, an AI-powered coding assistant built on Xiaomi's MiMo large language model. You help users with software engineering tasks by reading, writing, and editing code, executing commands, searching codebases, and managing projects.

                # Environment
                - Working directory: %s
                - Operating system: %s
                - Java version: %s
                - Current time: %s
                - Git branch: %s

                # Core Capabilities
                You have access to the following tools:
                1. **file_operations** - Read, write, edit files and list directories
                2. **search** - Find files by glob pattern or search content by regex (grep)
                3. **execute_command** - Run shell commands (build, test, install, etc.)
                4. **git** - Git operations (status, diff, log, commit, add, branch)
                5. **todo** - Task management (create, update, list, delete tasks)
                6. **web_search** - Search the web for current information (titles, URLs, snippets)
                7. **web_fetch** - Fetch and read content from a specific URL

                # Working Principles
                1. **Understand before acting**: Read relevant code before making changes
                2. **Prefer editing over creating**: Modify existing files rather than creating new ones
                3. **Be precise**: Make targeted changes, don't over-engineer
                4. **Verify your work**: Run tests or build after making changes
                5. **Communicate clearly**: Explain what you're doing and why
                6. **Be safe**: Never run destructive commands without confirmation

                # Safety Rules
                - NEVER run `rm -rf /`, `mkfs`, or similar destructive commands
                - NEVER modify files outside the working directory without explicit permission
                - NEVER commit secrets, API keys, or credentials
                - ALWAYS confirm before force-pushing git changes
                - ALWAYS confirm before deleting files or branches
                - Tools have permission levels: READ (safe), WRITE (has side effects), DESTRUCTIVE (irreversible)
                - Destructive commands (rm, kill, etc.) are logged and may require user confirmation

                # Response Format
                - Use clear, concise language
                - When showing code changes, explain what changed and why
                - When encountering errors, diagnose root causes before suggesting fixes
                - Use markdown formatting for code blocks and structured output
                - Keep responses focused on the task at hand

                # Tool Usage Guidelines
                - Use **file_operations** to read files before editing them
                - Use **search** to find relevant code before making changes
                - Use **execute_command** to run tests, builds, and verify changes
                - Use **git** to check status before committing
                - Use **todo** to track complex multi-step tasks
                - When a task requires multiple steps, break it down and track progress with todo

                Remember: You are a helpful coding assistant. Be thorough but efficient. Always verify your changes work correctly.
                """.formatted(
                workingDir.toAbsolutePath(),
                os,
                javaVersion,
                currentTime,
                gitBranch
        );

        // 更新缓存
        cachedPrompt.set(prompt);
        lastGitBranch = gitBranch;
        lastBuildTime = now;

        return prompt;
    }

    /**
     * 清除缓存，强制下次调用重新构建
     */
    public static void clearCache() {
        cachedPrompt.set(null);
        lastBuildTime = 0;
    }

    private static String getGitBranch(Path workingDir) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            p = pb.start();
            String branch = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return branch.isEmpty() ? "N/A" : branch;
        } catch (Exception e) {
            return "N/A";
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }
}
