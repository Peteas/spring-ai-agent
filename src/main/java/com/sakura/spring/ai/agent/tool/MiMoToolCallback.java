package com.sakura.spring.ai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

public class MiMoToolCallback implements ToolCallback {

    private final Tool tool;
    private final ToolDefinition toolDefinition;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public MiMoToolCallback(Tool tool) {
        this.tool = tool;
        try {
            String schema = objectMapper.writeValueAsString(tool.parameters());
            this.toolDefinition = DefaultToolDefinition.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .inputSchema(schema)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build tool definition for " + tool.name(), e);
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().build();
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> args = objectMapper.readValue(toolInput, Map.class);
            Tool.ToolResult result = tool.execute(args);
            return result.output();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public Tool getTool() {
        return tool;
    }
}
