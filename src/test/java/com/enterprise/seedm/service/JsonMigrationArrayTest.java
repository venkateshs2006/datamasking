package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JsonMigrationConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JsonMigrationArrayTest {

    private JsonMigrationService jsonMigrationService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        Faker faker = new Faker();
        MaskingConfigService maskingConfigService = new MaskingConfigService(List.of(), List.of(), List.of(), "Default16CharKey");
        FormatPreservingEncryptionService fpeService = new FormatPreservingEncryptionService(maskingConfigService);
        DataMaskingService dataMaskingService = new DataMaskingService(fpeService, null, maskingConfigService);
        jsonMigrationService = new JsonMigrationService(fpeService, faker, dataMaskingService);
    }

    @Test
    void testArraySfdGeneratesFakerArray() throws Exception {
        String json = "{\"primitive_array\": [\"apple\", \"banana\", \"cherry\"]}";
        JsonNode root = objectMapper.readTree(json);

        JsonMigrationConfig config = new JsonMigrationConfig();
        JsonMigrationConfig.RulesConfig rules = new JsonMigrationConfig.RulesConfig();
        rules.setMaskingColumns(List.of("primitive_array"));
        config.setRules(rules);

        jsonMigrationService.maskJsonTree(root, config);

        JsonNode arr = root.get("primitive_array");
        assertTrue(arr.isArray());
        assertEquals(3, arr.size());
        assertNotEquals("apple", arr.get(0).asText());
        assertNotEquals("banana", arr.get(1).asText());
        assertNotEquals("cherry", arr.get(2).asText());
    }

    @Test
    void testNestedArraySfdGeneratesFakerNestedArray() throws Exception {
        String json = "{\"nested_array\": [[\"a1\", \"a2\"], [\"b1\", \"b2\"]]}";
        JsonNode root = objectMapper.readTree(json);

        JsonMigrationConfig config = new JsonMigrationConfig();
        JsonMigrationConfig.RulesConfig rules = new JsonMigrationConfig.RulesConfig();
        rules.setMaskingColumns(List.of("nested_array"));
        config.setRules(rules);

        jsonMigrationService.maskJsonTree(root, config);

        JsonNode matrix = root.get("nested_array");
        assertTrue(matrix.isArray());
        assertEquals(2, matrix.size());
        assertTrue(matrix.get(0).isArray());
        assertTrue(matrix.get(1).isArray());
        assertNotEquals("a1", matrix.get(0).get(0).asText());
        assertNotEquals("b2", matrix.get(1).get(1).asText());
    }

    @Test
    void testArrayCmfGeneratesTextOrNumber() throws Exception {
        String json = "{\"num_array\": [10, 20], \"text_array\": [\"ORD-001\", \"ORD-002\"]}";
        JsonNode root = objectMapper.readTree(json);

        JsonMigrationConfig config = new JsonMigrationConfig();
        JsonMigrationConfig.RulesConfig rules = new JsonMigrationConfig.RulesConfig();
        rules.setCustomFormats(Map.of(
                "num_array", "NUM:POSITIVE:INTEGER:RANGE(100,500)",
                "text_array", "TEXT:SEC-{rand_alpha:4}"
        ));
        config.setRules(rules);

        jsonMigrationService.maskJsonTree(root, config);

        JsonNode numArr = root.get("num_array");
        assertTrue(numArr.isArray());
        assertTrue(numArr.get(0).isNumber());
        assertTrue(numArr.get(0).asInt() >= 100 && numArr.get(0).asInt() <= 500);

        JsonNode textArr = root.get("text_array");
        assertTrue(textArr.isArray());
        assertTrue(textArr.get(0).isTextual());
        assertTrue(textArr.get(0).asText().startsWith("SEC-"));
    }

    @Test
    void testArrayFixedDataAndNullCreatesArrayOfValues() throws Exception {
        String json = "{\"null_array\": [\"v1\", \"v2\"], \"fixed_array\": [\"x\", \"y\"]}";
        JsonNode root = objectMapper.readTree(json);

        JsonMigrationConfig config = new JsonMigrationConfig();
        JsonMigrationConfig.RulesConfig rules = new JsonMigrationConfig.RulesConfig();
        rules.setNullifyColumns(List.of("null_array"));
        rules.setDefaultValues(Map.of("fixed_array", "ANONYMIZED"));
        config.setRules(rules);

        jsonMigrationService.maskJsonTree(root, config);

        JsonNode nullArr = root.get("null_array");
        assertTrue(nullArr.isArray());
        assertEquals(2, nullArr.size());
        assertTrue(nullArr.get(0).isNull());
        assertTrue(nullArr.get(1).isNull());

        JsonNode fixedArr = root.get("fixed_array");
        assertTrue(fixedArr.isArray());
        assertEquals(2, fixedArr.size());
        assertEquals("ANONYMIZED", fixedArr.get(0).asText());
        assertEquals("ANONYMIZED", fixedArr.get(1).asText());
    }

    @Test
    void testNestedArrayFixedDataAndNullCreatesNestedArrayOfValues() throws Exception {
        String json = "{\"nested_null\": [[\"a\", \"b\"], [\"c\", \"d\"]], \"nested_fixed\": [[\"1\", \"2\"], [\"3\", \"4\"]]}";
        JsonNode root = objectMapper.readTree(json);

        JsonMigrationConfig config = new JsonMigrationConfig();
        JsonMigrationConfig.RulesConfig rules = new JsonMigrationConfig.RulesConfig();
        rules.setNullifyColumns(List.of("nested_null"));
        rules.setDefaultValues(Map.of("nested_fixed", "N/A"));
        config.setRules(rules);

        jsonMigrationService.maskJsonTree(root, config);

        JsonNode nNull = root.get("nested_null");
        assertTrue(nNull.isArray());
        assertTrue(nNull.get(0).isArray());
        assertTrue(nNull.get(0).get(0).isNull());
        assertTrue(nNull.get(1).get(1).isNull());

        JsonNode nFixed = root.get("nested_fixed");
        assertTrue(nFixed.isArray());
        assertTrue(nFixed.get(0).isArray());
        assertEquals("N/A", nFixed.get(0).get(0).asText());
        assertEquals("N/A", nFixed.get(1).get(1).asText());
    }

    @Test
    void testEmptyArrayHandling() throws Exception {
        String json = "{\"empty_array\": []}";
        JsonNode root = objectMapper.readTree(json);

        JsonMigrationConfig config = new JsonMigrationConfig();
        JsonMigrationConfig.RulesConfig rules = new JsonMigrationConfig.RulesConfig();
        rules.setMaskingColumns(List.of("empty_array"));
        config.setRules(rules);

        jsonMigrationService.maskJsonTree(root, config);

        JsonNode arr = root.get("empty_array");
        assertTrue(arr.isArray());
        assertTrue(arr.size() > 0);
    }
}
