package com.enterprise.seedm.service;

import com.enterprise.seedm.model.AiPiiDetectionResponse.PiiEntityInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QwenAiClientServiceTest {

    private QwenAiClientService qwenAiClientService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        qwenAiClientService = new QwenAiClientService(objectMapper);
    }

    @Test
    void testTestConnectionFailsGracefullyOnInvalidUrl() {
        boolean result = qwenAiClientService.testConnection(
                "http://localhost:9999/invalid/endpoint",
                "dummy-key",
                "qwen-turbo"
        );
        assertFalse(result);
    }
}
