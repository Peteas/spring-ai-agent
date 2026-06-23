package com.sakura.spring.ai.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "mimo")
public class AgentProperties {

    private Agent agent = new Agent();
    private Tool tool = new Tool();

    @Setter
    @Getter
    public static class Agent {
        private String workingDir = System.getProperty("user.dir");
        private int commandTimeout = 120;
        private int maxContextMessages = 50;
        private String safetyMode = "strict";
        private int maxToolCallRounds = 20;
        private int maxParallelToolCalls = 4;
        private int maxContextTokens = 32000;
        private int keepRecentTurns = 10;
        private String model = "mimo-v2.5-pro";
    }

    @Setter
    @Getter
    public static class Tool {
        private WebSearchConfig webSearch = new WebSearchConfig();
        private WebFetchConfig webFetch = new WebFetchConfig();
    }

    @Setter
    @Getter
    public static class WebSearchConfig {
        private boolean enabled = true;
        private String provider = "tavily";
        private String apiKey = "";
        private String baseUrl = "https://api.tavily.com";
        private int maxResults = 5;
        private int timeoutSeconds = 10;
    }

    @Setter
    @Getter
    public static class WebFetchConfig {
        private boolean enabled = true;
        private int maxContentSize = 30720;
        private int timeoutSeconds = 15;
    }

    // ── 快捷访问方法（保持向后兼容） ──────────────────────────

    public Path getWorkingDirPath() {
        return Paths.get(agent.getWorkingDir()).toAbsolutePath().normalize();
    }

    public int getCommandTimeout() {
        return agent.getCommandTimeout();
    }

    public int getMaxToolCallRounds() {
        return agent.getMaxToolCallRounds();
    }

    public int getMaxParallelToolCalls() {
        return agent.getMaxParallelToolCalls();
    }

    public int getMaxContextTokens() {
        return agent.getMaxContextTokens();
    }

    public int getKeepRecentTurns() {
        return agent.getKeepRecentTurns();
    }

    public int getMaxContextMessages() {
        return agent.getMaxContextMessages();
    }

    public String getSafetyMode() {
        return agent.getSafetyMode();
    }

    public String getModel() {
        return agent.getModel();
    }

    public boolean isStrictSafetyMode() {
        return "strict".equalsIgnoreCase(getSafetyMode());
    }

    public WebSearchConfig getWebSearch() {
        return tool.getWebSearch();
    }

    public WebFetchConfig getWebFetch() {
        return tool.getWebFetch();
    }
}
