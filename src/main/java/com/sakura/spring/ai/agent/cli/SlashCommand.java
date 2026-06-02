package com.sakura.spring.ai.agent.cli;

import com.sakura.spring.ai.agent.MiMoAgent;
import com.sakura.spring.ai.agent.tool.Tool;
import com.sakura.spring.ai.agent.tool.ToolRegistry;

import java.util.List;
import java.util.Map;

public class SlashCommand {

    private final MiMoAgent agent;
    private final ToolRegistry toolRegistry;
    private final StreamRenderer renderer;
    private String currentSessionId;

    public SlashCommand(MiMoAgent agent, ToolRegistry toolRegistry, StreamRenderer renderer, String sessionId) {
        this.agent = agent;
        this.toolRegistry = toolRegistry;
        this.renderer = renderer;
        this.currentSessionId = sessionId;
    }

    public boolean handle(String input) {
        if (!input.startsWith("/")) {
            return false;
        }

        String[] parts = input.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "/help" -> printHelp();
            case "/clear" -> clearScreen();
            case "/new" -> newSession();
            case "/sessions" -> listSessions();
            case "/tools" -> listTools();
            case "/todo" -> listTodos();
            case "/model" -> showModel();
            case "/exit", "/quit" -> {
                renderer.printInfo("Goodbye!");
                System.exit(0);
            }
            default -> renderer.printError("Unknown command: " + command + ". Type /help for available commands.");
        }

        return true;
    }

    private void printHelp() {
        System.out.println();
        System.out.println(StreamRenderer.BOLD + "  Available Commands:" + StreamRenderer.RESET);
        System.out.println();
        System.out.println("    /help          Show this help message");
        System.out.println("    /clear         Clear the screen");
        System.out.println("    /new           Start a new conversation session");
        System.out.println("    /sessions      List all conversation sessions");
        System.out.println("    /tools         List available tools");
        System.out.println("    /todo          Show task list");
        System.out.println("    /model         Show current model info");
        System.out.println("    /exit, /quit   Exit the application");
        System.out.println();
    }

    private void clearScreen() {
        System.out.print("\033[2J\033[H");
        System.out.flush();
    }

    private void newSession() {
        currentSessionId = "session-" + System.currentTimeMillis();
        agent.clearSession(currentSessionId);
        renderer.printInfo("New session started: " + currentSessionId);
    }

    private void listSessions() {
        List<String> sessions = agent.listSessions();
        if (sessions.isEmpty()) {
            renderer.printInfo("No active sessions.");
        } else {
            renderer.printInfo("Active sessions:");
            for (String session : sessions) {
                String marker = session.equals(currentSessionId) ? " (current)" : "";
                System.out.println("    - " + session + marker);
            }
        }
    }

    private void listTools() {
        renderer.printInfo("Available tools:");
        for (var callback : toolRegistry.getAllCallbacks()) {
            System.out.println(StreamRenderer.BOLD + "    " + callback.getToolDefinition().name() + StreamRenderer.RESET);
            System.out.println("      " + callback.getToolDefinition().description());
        }
    }

    private void listTodos() {
        Tool.ToolResult result = toolRegistry.execute("todo", Map.of("action", "list"));
        renderer.printInfo("Tasks:");
        for (String line : result.output().split("\n")) {
            System.out.println("    " + line);
        }
    }

    private void showModel() {
        renderer.printInfo("Current model: mimo-v2.5-pro (Xiaomi MiMo)");
        renderer.printInfo("API: OpenAI-compatible (supports Function Calling & Thinking Mode)");
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }
}
