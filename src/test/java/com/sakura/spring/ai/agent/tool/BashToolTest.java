package com.sakura.spring.ai.agent.tool;

import com.sakura.spring.ai.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    private BashTool bashTool;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.setCommandTimeout(30);
        props.setSafetyMode("strict");
        bashTool = new BashTool(props);
    }

    @Test
    void blocksDangerousRmRoot() {
        Tool.ToolResult result = bashTool.execute(Map.of("command", "rm -rf /"));
        assertTrue(result.isError());
    }

    @Test
    void blocksSubshellWrapper() {
        Tool.ToolResult result = bashTool.execute(Map.of("command", "bash -c 'ls'"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("Subshell"));
    }

    @Test
    void blocksCommandSubstitution() {
        Tool.ToolResult result = bashTool.execute(Map.of("command", "echo $(whoami)"));
        assertTrue(result.isError());
    }

    @Test
    void blocksBacktickSubstitution() {
        Tool.ToolResult result = bashTool.execute(Map.of("command", "echo `whoami`"));
        assertTrue(result.isError());
    }

    @Test
    void blocksDisallowedCommand() {
        Tool.ToolResult result = bashTool.execute(Map.of("command", "reboot"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("not allowed"));
    }

    @Test
    void allowsSafeCommand() {
        Tool.ToolResult result = bashTool.execute(Map.of("command", "echo hello"));
        assertFalse(result.isError());
        assertTrue(result.output().contains("hello"));
    }

    @Test
    void requiresCommand() {
        Tool.ToolResult result = bashTool.execute(Map.of());
        assertTrue(result.isError());
    }
}
