package com.example.bilibilimusic.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptVersionServiceTest {

    @Test
    void registerAndSwitchVersion() {
        PromptVersionService service = new PromptVersionService();

        service.registerPrompt("test_node", "v1", "t1");
        assertEquals("v1", service.getCurrentVersion("test_node"));
        assertEquals("t1", service.getPromptTemplate("test_node"));

        service.registerPrompt("test_node", "v2", "t2");
        assertEquals("v2", service.getCurrentVersion("test_node"));
        assertEquals("t2", service.getPromptTemplate("test_node"));

        service.switchVersion("test_node", "v1");
        assertEquals("v1", service.getCurrentVersion("test_node"));
        assertEquals("t1", service.getPromptTemplate("test_node"));
    }
}

