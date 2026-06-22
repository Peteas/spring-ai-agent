package com.sakura.spring.ai.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptTest {

    @BeforeEach
    void clearCache() {
        SystemPrompt.clearCache();
    }

    @Test
    void buildsPromptWithWorkingDir() {
        Path dir = Path.of(System.getProperty("user.dir"));
        String prompt = SystemPrompt.build(dir);
        assertTrue(prompt.contains("MiMo Code Agent"));
        assertTrue(prompt.contains(dir.toAbsolutePath().toString()));
    }

    @Test
    void cachesPromptWithinTtl() {
        Path dir = Path.of(System.getProperty("user.dir"));
        String first = SystemPrompt.build(dir);
        String second = SystemPrompt.build(dir);
        assertSame(first, second);
    }

    @Test
    void clearCacheForcesRebuild() {
        Path dir = Path.of(System.getProperty("user.dir"));
        String first = SystemPrompt.build(dir);
        SystemPrompt.clearCache();
        String second = SystemPrompt.build(dir);
        assertNotSame(first, second);
        assertEquals(first, second);
    }
}
