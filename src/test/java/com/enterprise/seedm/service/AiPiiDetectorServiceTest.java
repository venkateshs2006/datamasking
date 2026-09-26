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

        // Relational Keys in Heuristic Engine
        assertTrue(response.getPrimaryKeyColumns().contains("customer.customer_id"));
        assertTrue(response.getPrimaryKeyColumns().contains("address.address_id"));
        assertTrue(response.getPrimaryKeyColumns().contains("staff.staff_id"));
        assertTrue(response.getForeignKeyColumns().contains("address.city_id"));
        assertTrue(response.getForeignKeyColumns().contains("staff.store_id"));
        assertTrue(response.getUniqueKeyColumns().contains("staff.username"));
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
        assertTrue(response.getPrimaryKeyColumns().contains("payment.payment_id"));
        assertTrue(response.getForeignKeyColumns().contains("payment.customer_id"));
        assertTrue(response.getForeignKeyColumns().contains("payment.staff_id"));
        assertTrue(response.getForeignKeyColumns().contains("payment.rental_id"));
    }

    @Test
    void testHeuristicEngineComprehensivePciSecureAndKeys() {
        AiPiiDetectionRequest request = AiPiiDetectionRequest.builder()
                .tables(List.of("users", "orders", "payments", "user_roles", "patients"))
                .tableColumns(Map.of(
                        "users", List.of("id", "username", "password_hash", "api_key", "client_ip", "gender"),
                        "orders", List.of("order_id", "customer_id", "tenant_id", "total_amount"),
                        "payments", List.of("payment_id", "card_number", "cvv", "card_exp", "iban", "swift"),
                        "user_roles", List.of("user_id", "role_id"),
                        "patients", List.of("id", "mrn", "diagnosis")
                ))
                .build();

        AiPiiDetectionResponse response = detectorService.detectPii(request);

        assertNotNull(response);
        assertEquals("In-Memory Heuristic Engine", response.getEngineUsed());

        // 1. Primary Keys
        assertTrue(response.getPrimaryKeyColumns().contains("users.id"));
        assertTrue(response.getPrimaryKeyColumns().contains("orders.order_id"));
        assertTrue(response.getPrimaryKeyColumns().contains("payments.payment_id"));
        assertTrue(response.getPrimaryKeyColumns().contains("patients.id"));

        // 2. Foreign Keys
        assertTrue(response.getForeignKeyColumns().contains("orders.customer_id"));
        AiPiiDetectionResponse.PiiEntityInfo fkInfo = response.getDetectedEntities().get("orders.customer_id");
        assertNotNull(fkInfo);
        assertEquals("FOREIGN_KEY", fkInfo.getKeyType());
        assertEquals("customer", fkInfo.getTargetTable());

        // 3. Table Mapping / Multi-tenant & Junction Keys
        assertTrue(response.getTableMappingColumns().contains("orders.tenant_id"));
        assertTrue(response.getTableMappingColumns().contains("user_roles.user_id"));
        assertTrue(response.getTableMappingColumns().contains("user_roles.role_id"));

        // 4. Unique Keys
        assertTrue(response.getUniqueKeyColumns().contains("users.username"));

        // 5. PCI & Financial Data
        assertTrue(response.getMaskingColumns().contains("payments.card_number"));
        assertTrue(response.getMaskingColumns().contains("payments.cvv"));
        assertTrue(response.getPartialMaskingColumns().contains("payments.card_exp"));
        assertTrue(response.getMaskingColumns().contains("payments.iban"));
        assertTrue(response.getMaskingColumns().contains("payments.swift"));
        assertTrue(response.getMaskingColumns().contains("orders.total_amount"));

        // 6. Secure Credentials & Secrets
        assertTrue(response.getMaskingColumns().contains("users.password_hash"));
        assertTrue(response.getMaskingColumns().contains("users.api_key"));

        // 7. Healthcare PHI
        assertTrue(response.getConstraintColumns().contains("patients.mrn"));
        assertTrue(response.getConstraintColumns().contains("patients.diagnosis"));

        // 8. Digital & Demographics
        assertTrue(response.getMaskingColumns().contains("users.client_ip"));
        assertTrue(response.getMaskingColumns().contains("users.gender"));
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

    @Test
    void testDetectPiiWithQwenAiKeysAndMappings() throws Exception {
        Map<String, PiiEntityInfo> mockQwenEntities = Map.of(
                "customer.id", PiiEntityInfo.builder()
                        .table("customer")
                        .column("id")
                        .category("PRIMARY_KEY")
                        .ruleType("FPH")
                        .keyType("PRIMARY_KEY")
                        .build(),
                "orders.customer_id", PiiEntityInfo.builder()
                        .table("orders")
                        .column("customer_id")
                        .category("FOREIGN_KEY")
                        .ruleType("FPH")
                        .keyType("FOREIGN_KEY")
                        .targetTable("customer")
                        .targetColumn("id")
                        .build(),
                "users.username", PiiEntityInfo.builder()
                        .table("users")
                        .column("username")
                        .category("UNIQUE_KEY")
                        .ruleType("SFD")
                        .keyType("UNIQUE_KEY")
                        .build(),
                "user_roles.role_id", PiiEntityInfo.builder()
                        .table("user_roles")
                        .column("role_id")
                        .category("TABLE_MAPPING")
                        .ruleType("FPH")
                        .keyType("TABLE_MAPPING")
                        .targetTable("roles")
                        .targetColumn("id")
                        .build()
        );

        when(qwenAiClientService.detectPiiWithQwen(any(), any(), any(), any()))
                .thenReturn(mockQwenEntities);

        AiPiiDetectionRequest request = AiPiiDetectionRequest.builder()
                .tables(List.of("customer", "orders", "users", "user_roles"))
                .tableColumns(Map.of(
                        "customer", List.of("id"),
                        "orders", List.of("customer_id"),
                        "users", List.of("username"),
                        "user_roles", List.of("role_id")
                ))
                .qwenApiUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions")
                .qwenApiKey("sk-test-key-12345")
                .qwenModel("qwen-turbo")
                .build();

        AiPiiDetectionResponse response = detectorService.detectPii(request);

        assertNotNull(response);
        assertEquals(4, response.getTotalPiiColumnsFound());
        assertEquals(4, response.getTotalKeyColumnsFound());

        assertTrue(response.getPrimaryKeyColumns().contains("customer.id"));
        assertTrue(response.getForeignKeyColumns().contains("orders.customer_id"));
        assertTrue(response.getUniqueKeyColumns().contains("users.username"));
        assertTrue(response.getTableMappingColumns().contains("user_roles.role_id"));

        assertTrue(response.getConstraintColumns().contains("customer.id"));
        assertTrue(response.getConstraintColumns().contains("orders.customer_id"));
        assertTrue(response.getMaskingColumns().contains("users.username"));
        assertTrue(response.getConstraintColumns().contains("user_roles.role_id"));
    }
}
