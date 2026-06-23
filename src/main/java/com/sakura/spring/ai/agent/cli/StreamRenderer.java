package com.sakura.spring.ai.agent.cli;

/**
 * Renders agent output in the terminal with ANSI colors and formatting.
 */
public class StreamRenderer {

    // ANSI color codes
    public static final String RESET = "\033[0m";
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";
    public static final String RED = "\033[31m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String BLUE = "\033[34m";
    public static final String MAGENTA = "\033[35m";
    public static final String CYAN = "\033[36m";
    public static final String GRAY = "\033[90m";
    public static final String BG_BLUE = "\033[44m";
    public static final String BG_GRAY = "\033[100m";

    public void printWelcome() {
        System.out.println();
        System.out.println(BOLD + CYAN + "╔══════════════════════════════════════════╗" + RESET);
        System.out.println(BOLD + CYAN + "║     MiMo Code Agent v0.1.0              ║" + RESET);
        System.out.println(BOLD + CYAN + "║     Powered by Xiaomi MiMo v2.5 Pro     ║" + RESET);
        System.out.println(BOLD + CYAN + "╚══════════════════════════════════════════╝" + RESET);
        System.out.println();
        System.out.println(GRAY + "  Type your message to start. /help for commands. /exit to quit." + RESET);
        System.out.println();
    }

    public void printThinkingStart() {
        System.out.println();
        System.out.println(GRAY + "  ┌─ Thinking ──────────────────────────────" + RESET);
    }

    public void printThinkingChunk(String content) {
        System.out.print(GRAY + "  │ " + content + RESET);
    }

    public void printThinkingEnd() {
        System.out.println(GRAY + "  └─────────────────────────────────────────" + RESET);
        System.out.println();
    }

    public void printToolCall(String toolName, String arguments) {
        System.out.println();
        System.out.println(BLUE + BOLD + "  ⚙ Tool Call: " + toolName + RESET);
        System.out.println(BLUE + "  ┌─ Arguments ─────────────────────────────" + RESET);
        // Pretty print arguments (truncate if too long)
        String args = arguments.length() > 500 ? arguments.substring(0, 500) + "..." : arguments;
        for (String line : args.split("\n")) {
            System.out.println(BLUE + "  │ " + line + RESET);
        }
        System.out.println(BLUE + "  └─────────────────────────────────────────" + RESET);
    }

    public void printToolResult(String toolName, String output, boolean isError) {
        String color = isError ? RED : GREEN;
        String prefix = isError ? "  ✗ Error" : "  ✓ Result";

        System.out.println(color + prefix + " (" + toolName + ")" + RESET);
        System.out.println(color + "  ┌─────────────────────────────────────────" + RESET);
        // Truncate long output
        String displayOutput = output.length() > 2000 ? output.substring(0, 2000) + "\n... (truncated)" : output;
        for (String line : displayOutput.split("\n")) {
            System.out.println(color + "  │ " + line + RESET);
        }
        System.out.println(color + "  └─────────────────────────────────────────" + RESET);
        System.out.println();
    }

    public void printAnswer(String content) {
        System.out.println();
        System.out.println(BOLD + "  MiMo:" + RESET);
        // Indent the answer
        for (String line : content.split("\n")) {
            System.out.println("  " + line);
        }
        System.out.println();
    }

    public void printError(String message) {
        System.out.println();
        System.out.println(RED + BOLD + "  ✗ Error: " + message + RESET);
        System.out.println();
    }

    public void printInfo(String message) {
        System.out.println(CYAN + "  ℹ " + message + RESET);
    }

    public void printSystemMessage(String message) {
        System.out.println(YELLOW + "  " + message + RESET);
    }

    public void printConfirmationRequired(String toolName, String reason) {
        System.out.println();
        System.out.println(YELLOW + BOLD + "  ⚠ CONFIRMATION REQUIRED" + RESET);
        System.out.println(YELLOW + "  Tool: " + toolName + RESET);
        System.out.println(YELLOW + "  Reason: " + reason + RESET);
        System.out.println();
    }

    public void printSeparator() {
        System.out.println(GRAY + "  ─────────────────────────────────────────" + RESET);
    }

    public String colorize(String text, String color) {
        return color + text + RESET;
    }
}
