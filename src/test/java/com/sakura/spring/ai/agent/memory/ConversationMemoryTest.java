package com.sakura.spring.ai.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory(new ObjectMapper());
    }

    @Test
    void addAndRetrieveMessages() {
        memory.addUserMessage("s1", "hello");
        memory.addAssistantMessage("s1", "hi there");

        List<org.springframework.ai.chat.messages.Message> messages = memory.getMessages("s1");
        assertEquals(2, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertInstanceOf(AssistantMessage.class, messages.get(1));
    }

    @Test
    void removeLastTurnRemovesUserAndFollowUps() {
        memory.addUserMessage("s1", "first");
        memory.addAssistantMessage("s1", "answer1");
        memory.addUserMessage("s1", "second");
        memory.addAssistantMessage("s1", "answer2");

        memory.removeLastTurn("s1");

        List<org.springframework.ai.chat.messages.Message> messages = memory.getMessages("s1");
        assertEquals(2, messages.size());
        assertEquals("first", ((UserMessage) messages.get(0)).getText());
        assertEquals("answer1", messages.get(1).getText());
    }

    @Test
    void clearSession() {
        memory.addUserMessage("s1", "hello");
        memory.clearSession("s1");
        assertTrue(memory.getMessages("s1").isEmpty());
        assertFalse(memory.hasSession("s1"));
    }
}
