package com.sakura.spring.ai.agent.cli;

import com.sakura.spring.ai.agent.MiMoAgent;
import com.sakura.spring.ai.agent.tool.ToolRegistry;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "mimo.agent.cli-mode", havingValue = "true")
public class CliRunner implements CommandLineRunner {

    private final MiMoAgent agent;
    private final ToolRegistry toolRegistry;

    public CliRunner(MiMoAgent agent, ToolRegistry toolRegistry) {
        this.agent = agent;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void run(String... args) throws Exception {
        StreamRenderer renderer = new StreamRenderer();
        String sessionId = "session-" + System.currentTimeMillis();

        SlashCommand slashCommand = new SlashCommand(agent, toolRegistry, renderer, sessionId);

        // Setup JLine terminal
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        // Build completions for slash commands
        List<String> completions = new ArrayList<>();
        completions.add("/help");
        completions.add("/clear");
        completions.add("/new");
        completions.add("/sessions");
        completions.add("/tools");
        completions.add("/todo");
        completions.add("/model");
        completions.add("/exit");
        completions.add("/quit");

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(completions))
                .build();

        renderer.printWelcome();

        // Main interaction loop
        while (true) {
            try {
                String input = reader.readLine("You> ");

                if (input == null || input.isBlank()) {
                    continue;
                }

                input = input.trim();

                // Handle slash commands
                if (slashCommand.handle(input)) {
                    continue;
                }

                // Process through agent
                renderer.printSeparator();

                agent.chat(slashCommand.getCurrentSessionId(), input, new MiMoAgent.AgentCallback() {
                    private boolean answerStarted = false;

                    @Override
                    public void onToolCall(String toolName, String arguments) {
                        renderer.printToolCall(toolName, arguments);
                    }

                    @Override
                    public void onToolResult(String toolName, String output, boolean isError) {
                        renderer.printToolResult(toolName, output, isError);
                    }

                    @Override
                    public void onAnswerChunk(String chunk) {
                        if (!answerStarted) {
                            System.out.println();
                            System.out.print(StreamRenderer.BOLD + "  MiMo:" + StreamRenderer.RESET + " ");
                            answerStarted = true;
                        }
                        System.out.print(chunk);
                    }

                    @Override
                    public void onAnswer(String answer) {
                        if (!answerStarted) {
                            renderer.printAnswer(answer);
                        }
                        System.out.println();
                        System.out.println();
                    }

                    @Override
                    public void onThinking(String content) {
                        renderer.printThinkingChunk(content);
                    }

                    @Override
                    public void onError(String error) {
                        renderer.printError(error);
                    }
                });

            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Ctrl-C")) {
                    renderer.printInfo("Use /exit to quit.");
                } else {
                    renderer.printError("Unexpected error: " + e.getMessage());
                }
            }
        }
    }
}
