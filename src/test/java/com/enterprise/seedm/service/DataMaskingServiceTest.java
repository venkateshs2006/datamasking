package com.enterprise.seedm.service;

import com.enterprise.seedm.model.ColumnMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class DataMaskingServiceTest {

    private DataMaskingService dataMaskingService;
    private MaskingConfigService maskingConfigService;
    private FormatPreservingEncryptionService fpeService;
    private TableDiscoveryService tableDiscoveryService;

    @BeforeEach
    void setUp() {
        maskingConfigService = new MaskingConfigService(
                List.of("customers.name", "customers.email", "customers.ssn"),
                List.of("customers.customer_id", "customers.tax_id"),
                List.of("customers.credit_card", "customers.phone"),
                "SecureSaltKey123456"
        );

        fpeService = new FormatPreservingEncryptionService(maskingConfigService);
        tableDiscoveryService = Mockito.mock(TableDiscoveryService.class);

        List<ColumnMetadata> mockColumns = List.of(
                new ColumnMetadata("customer_id", "INTEGER", "NO", null, 10, 0),
                new ColumnMetadata("name", "VARCHAR", "NO", 100, null, null),
                new ColumnMetadata("email", "VARCHAR", "NO", 100, null, null),
                new ColumnMetadata("ssn", "VARCHAR", "NO", 20, null, null),
                new ColumnMetadata("credit_card", "VARCHAR", "NO", 30, null, null),
                new ColumnMetadata("phone", "VARCHAR", "NO", 30, null, null),
                new ColumnMetadata("notes", "VARCHAR", "YES", 200, null, null)
        );
        when(tableDiscoveryService.getTableColumnMetadata(anyString())).thenReturn(mockColumns);

        dataMaskingService = new DataMaskingService(fpeService, tableDiscoveryService, maskingConfigService);
    }

    @Test
    void testMaskDataRelationalTable() {
        Map<String, Object> inputRow = new HashMap<>();
        inputRow.put("customer_id", 1001);
        inputRow.put("name", "John Doe");
        inputRow.put("email", "john.doe@example.com");
        inputRow.put("credit_card", "4532-1234-5678-9012");
        inputRow.put("phone", "+1-555-123-4567");
        inputRow.put("notes", "VIP Customer Account");

        Map<String, Object> masked = dataMaskingService.maskData("customers", inputRow);

        assertNotNull(masked);
        // Constraint / FPE check: customer_id is encrypted deterministically
        assertNotNull(masked.get("customer_id"));
        assertNotEquals(1001, masked.get("customer_id"));

        // Full Synthetic Masking (SFD): name & email masked
        assertNotNull(masked.get("name"));
        assertNotEquals("John Doe", masked.get("name"));

        assertNotNull(masked.get("email"));
        assertNotEquals("john.doe@example.com", masked.get("email"));

        // Partial Masking (PMD): credit_card & phone preserves ending 4 chars
        String maskedCard = (String) masked.get("credit_card");
        assertNotNull(maskedCard);
        assertTrue(maskedCard.endsWith("9012"));

        String maskedPhone = (String) masked.get("phone");
        assertNotNull(maskedPhone);
        assertTrue(maskedPhone.endsWith("4567"));

        // Unconfigured column is untouched
        assertEquals("VIP Customer Account", masked.get("notes"));
    }

    @Test
    void testMaskNoSqlNestedDocument() {
        Map<String, Object> userDoc = new HashMap<>();
        userDoc.put("customer_id", 5005);
        userDoc.put("name", "Jane Smith");

        Map<String, Object> address = new HashMap<>();
        address.put("street", "100 Broadway");
        address.put("city", "New York");
        userDoc.put("address", address);

        Map<String, Object> masked = dataMaskingService.maskNoSqlData("customers", userDoc);

        assertNotNull(masked);
        assertNotEquals("Jane Smith", masked.get("name"));
        assertTrue(masked.get("address") instanceof Map);
    }
}
