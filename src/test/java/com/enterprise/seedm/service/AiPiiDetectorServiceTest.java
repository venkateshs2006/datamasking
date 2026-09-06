package com.enterprise.seedm.service;

import com.enterprise.seedm.model.AiPiiDetectionRequest;
import com.enterprise.seedm.model.AiPiiDetectionResponse;
import com.enterprise.seedm.model.AiPiiDetectionResponse.PiiEntityInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AiPiiDetectorServiceTest {

    @Mock
    private TableDiscoveryService tableDiscoveryService;

    @Mock
    private QwenAiClientService qwenAiClientService;

    private AiPiiDetectorService detectorService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        detectorService = new AiPiiDetectorService(tableDiscoveryService, qwenAiClientService);
    }

    @Test
    void testDetectPiiWithExplicitColumns() {
        AiPiiDetectionRequest request = AiPiiDetectionRequest.builder()
                .tables(List.of("customer", "address", "staff"))
                .tableColumns(Map.of(
                        "customer", List.of("customer_id", "first_name", "last_name", "email", "active", "dob", "ssn"),
                        "address", List.of("address_id", "address", "district", "city_id", "postal_code", "phone"),
                        "staff", List.of("staff_id", "first_name", "last_name", "email", "store_id", "username", "salary")
                ))
                .build();

        AiPiiDetectionResponse response = detectorService.detectPii(request);

        assertNotNull(response);
        assertTrue(response.getTotalPiiColumnsFound() >= 10);
        assertEquals("In-Memory Heuristic Engine", response.getEngineUsed());

        // SFD standard faker data validations
        assertTrue(response.getMaskingColumns().contains("customer.first_name"));
        assertTrue(response.getMaskingColumns().contains("customer.last_name"));
        assertTrue(response.getMaskingColumns().contains("customer.email"));
        assertTrue(response.getMaskingColumns().contains("address.address"));
        assertTrue(response.getMaskingColumns().contains("address.postal_code"));
        assertTrue(response.getMaskingColumns().contains("address.phone"));
        assertTrue(response.getMaskingColumns().contains("staff.username"));
        assertTrue(response.getMaskingColumns().contains("staff.salary"));

        // PMD date shifting validation
        assertTrue(response.getPartialMaskingColumns().contains("customer.dob"));

        // FPH format preserving encryption validation
        assertTrue(response.getConstraintColumns().contains("customer.ssn"));

        // Verify entity details
        AiPiiDetectionResponse.PiiEntityInfo emailInfo = response.getDetectedEntities().get("customer.email");
        assertNotNull(emailInfo);
        assertEquals("EMAIL", emailInfo.getCategory());
        assertEquals("SFD", emailInfo.getRuleType());
        assertEquals("faker.internet().emailAddress()", emailInfo.getFakerMethod());
    }

    @Test
    void testDetectPiiWithDynamicDiscovery() {
        when(tableDiscoveryService.getTableColumns("payment"))
                .thenReturn(List.of("payment_id", "customer_id", "staff_id", "rental_id", "amount", "card_number", "payment_date"));

        AiPiiDetectionRequest request = AiPiiDetectionRequest.builder()
                .tables(List.of("payment"))
                .build();

        AiPiiDetectionResponse response = detectorService.detectPii(request);

        assertNotNull(response);
        assertTrue(response.getMaskingColumns().contains("payment.card_number"));
        assertTrue(response.getMaskingColumns().contains("payment.amount"));
    }

    @Test
    void testDetectPiiWithQwenAiSuccess() throws Exception {
        Map<String, PiiEntityInfo> mockQwenEntities = Map.of(
                "customer.custom_email_field", PiiEntityInfo.builder()
                        .table("customer")
                        .column("custom_email_field")
                        .category("EMAIL")
                        .ruleType("SFD")
                        .fakerMethod("faker.internet().emailAddress()")
                        .confidence(0.99)
                        .reason("Detected customer email by Qwen LLM")
                        .build()
        );

        when(qwenAiClientService.detectPiiWithQwen(any(), anyString(), anyString(), anyString()))
                .thenReturn(mockQwenEntities);

        AiPiiDetectionRequest request = AiPiiDetectionRequest.builder()
                .tables(List.of("customer"))
                .tableColumns(Map.of("customer", List.of("custom_email_field", "regular_id")))
                .qwenApiUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions")
                .qwenApiKey("sk-test-key-12345")
                .qwenModel("qwen-turbo")
                .build();

        AiPiiDetectionResponse response = detectorService.detectPii(request);

        assertNotNull(response);
        assertEquals(1, response.getTotalPiiColumnsFound());
        assertTrue(response.getEngineUsed().contains("Qwen AI LLM"));
        assertTrue(response.getMaskingColumns().contains("customer.custom_email_field"));
    }
}
