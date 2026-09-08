package com.enterprise.seedm.service;

import com.enterprise.seedm.model.AiPiiDetectionResponse.PiiEntityInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

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

    @Test
    void testBuildChatModelCreatesConfiguredInstance() {
        ChatModel chatModel = qwenAiClientService.buildChatModel(
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
                "sk-test-key",
                "qwen-turbo",
                0.2,
                100
        );
        assertNotNull(chatModel);
    }

    @Test
    void testBuildChatModelWithDefaultUrlAndModel() {
        ChatModel chatModel = qwenAiClientService.buildChatModel(
                null,
                null,
                null,
                null,
                null
        );
        assertNotNull(chatModel);
    }

    @Test
    void testParseEntitiesFromJsonDirect() {
        String json = """
                {
                  "detectedEntities": {
                    "customer.email": {
                      "table": "customer",
                      "column": "email",
                      "category": "EMAIL",
                      "ruleType": "SFD",
                      "fakerMethod": "faker.internet().emailAddress()",
                      "confidence": 0.99,
                      "reason": "Email address pattern"
                    }
                  }
                }
                """;

        Map<String, PiiEntityInfo> entities = qwenAiClientService.parseEntitiesFromJson(json);
        assertNotNull(entities);
        assertEquals(1, entities.size());
        assertTrue(entities.containsKey("customer.email"));
        assertEquals("EMAIL", entities.get("customer.email").getCategory());
        assertEquals("SFD", entities.get("customer.email").getRuleType());
        assertEquals("faker.internet().emailAddress()", entities.get("customer.email").getFakerMethod());
    }

    @Test
    void testParseEntitiesFromJsonWithMarkdownCodeFence() {
        String markdownResponse = """
                ```json
                {
                  "detectedEntities": {
                    "users.dob": {
                      "table": "users",
                      "column": "dob",
                      "category": "DATE_OF_BIRTH",
                      "ruleType": "PMD",
                      "fakerMethod": "DateShifter(±365d)",
                      "confidence": 0.95,
                      "reason": "Birth date"
                    }
                  }
                }
                ```
                """;

        Map<String, PiiEntityInfo> entities = qwenAiClientService.parseEntitiesFromJson(markdownResponse);
        assertNotNull(entities);
        assertEquals(1, entities.size());
        assertTrue(entities.containsKey("users.dob"));
        assertEquals("PMD", entities.get("users.dob").getRuleType());
        assertEquals("DateShifter(±365d)", entities.get("users.dob").getFakerMethod());
    }

    @Test
    void testParseEntitiesFromJsonEmpty() {
        Map<String, PiiEntityInfo> emptyMap = qwenAiClientService.parseEntitiesFromJson("");
        assertNotNull(emptyMap);
        assertTrue(emptyMap.isEmpty());

        Map<String, PiiEntityInfo> nullMap = qwenAiClientService.parseEntitiesFromJson(null);
        assertNotNull(nullMap);
        assertTrue(nullMap.isEmpty());
    }
}

