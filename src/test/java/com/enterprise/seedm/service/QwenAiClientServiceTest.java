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

    @Test
    void testParseEntitiesFromJsonWithPiiPciSecureAndKeyMappings() {
        String json = """
                {
                  "detectedEntities": {
                    "customers.id": {
                      "table": "customers",
                      "column": "id",
                      "category": "PRIMARY_KEY",
                      "ruleType": "FPH",
                      "fakerMethod": "DeterministicFPH()",
                      "confidence": 0.99,
                      "reason": "Primary key surrogate identifier",
                      "keyType": "PRIMARY_KEY"
                    },
                    "customers.email": {
                      "table": "customers",
                      "column": "email",
                      "category": "EMAIL",
                      "ruleType": "SFD",
                      "fakerMethod": "faker.internet().emailAddress()",
                      "confidence": 0.99,
                      "reason": "Customer email contact"
                    },
                    "orders.customer_id": {
                      "table": "orders",
                      "column": "customer_id",
                      "category": "FOREIGN_KEY",
                      "ruleType": "FPH",
                      "fakerMethod": "DeterministicFPH()",
                      "confidence": 0.98,
                      "reason": "Foreign key referencing customers.id",
                      "keyType": "FOREIGN_KEY",
                      "targetTable": "customers",
                      "targetColumn": "id"
                    },
                    "payments.card_number": {
                      "table": "payments",
                      "column": "card_number",
                      "category": "CREDIT_CARD",
                      "ruleType": "SFD",
                      "fakerMethod": "faker.finance().creditCard()",
                      "confidence": 0.99,
                      "reason": "PCI credit card number"
                    },
                    "users.password_hash": {
                      "table": "users",
                      "column": "password_hash",
                      "category": "PASSWORD_SECRET",
                      "ruleType": "SFD",
                      "fakerMethod": "faker.internet().password(12, true)",
                      "confidence": 0.97,
                      "reason": "Sensitive password hash credential"
                    },
                    "users.username": {
                      "table": "users",
                      "column": "username",
                      "category": "UNIQUE_KEY",
                      "ruleType": "SFD",
                      "fakerMethod": "faker.name().fullName()",
                      "confidence": 0.95,
                      "reason": "Unique user handle",
                      "keyType": "UNIQUE_KEY"
                    },
                    "user_roles.role_id": {
                      "table": "user_roles",
                      "column": "role_id",
                      "category": "TABLE_MAPPING",
                      "ruleType": "FPH",
                      "fakerMethod": "DeterministicFPH()",
                      "confidence": 0.96,
                      "reason": "Junction table mapping to roles table",
                      "keyType": "TABLE_MAPPING",
                      "targetTable": "roles",
                      "targetColumn": "id"
                    }
                  }
                }
                """;

        Map<String, PiiEntityInfo> entities = qwenAiClientService.parseEntitiesFromJson(json);
        assertNotNull(entities);
        assertEquals(7, entities.size());

        // Primary Key
        PiiEntityInfo pk = entities.get("customers.id");
        assertNotNull(pk);
        assertEquals("PRIMARY_KEY", pk.getKeyType());
        assertEquals("FPH", pk.getRuleType());

        // PII
        PiiEntityInfo email = entities.get("customers.email");
        assertNotNull(email);
        assertEquals("EMAIL", email.getCategory());
        assertEquals("SFD", email.getRuleType());

        // Foreign Key
        PiiEntityInfo fk = entities.get("orders.customer_id");
        assertNotNull(fk);
        assertEquals("FOREIGN_KEY", fk.getKeyType());
        assertEquals("customers", fk.getTargetTable());
        assertEquals("id", fk.getTargetColumn());

        // PCI
        PiiEntityInfo cc = entities.get("payments.card_number");
        assertNotNull(cc);
        assertEquals("CREDIT_CARD", cc.getCategory());

        // Secure secret
        PiiEntityInfo pwd = entities.get("users.password_hash");
        assertNotNull(pwd);
        assertEquals("PASSWORD_SECRET", pwd.getCategory());

        // Unique Key
        PiiEntityInfo uk = entities.get("users.username");
        assertNotNull(uk);
        assertEquals("UNIQUE_KEY", uk.getKeyType());

        // Table Mapping
        PiiEntityInfo mapping = entities.get("user_roles.role_id");
        assertNotNull(mapping);
        assertEquals("TABLE_MAPPING", mapping.getKeyType());
        assertEquals("roles", mapping.getTargetTable());
    }
}

