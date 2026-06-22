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
@ConfigurationProperties(prefix = "mimo.agent")
public class AgentProperties {

    private String workingDir = System.getProperty("user.dir");
    private int commandTimeout = 120;
    private int maxContextMessages = 50;
    private String safetyMode = "strict";
    private int maxToolCallRounds = 20;
    private String model = "mimo-v2.5-pro";

    public Path getWorkingDirPath() {
        return Paths.get(workingDir).toAbsolutePath().normalize();
    }

    public boolean isStrictSafetyMode() {
        return "strict".equalsIgnoreCase(safetyMode);
    }
}
