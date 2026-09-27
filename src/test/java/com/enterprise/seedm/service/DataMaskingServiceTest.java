package com.enterprise.seedm.service;

import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.model.ConstraintMetadata;
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

    @Test
    void testMaskDataWithRulesSchemaPrefixed() {
        Map<String, Object> inputRow = new HashMap<>();
        inputRow.put("customer_id", 1001);
        inputRow.put("name", "John Doe");
        inputRow.put("phone", "+1-555-123-4567");

        List<String> maskingCols = List.of("public.customers.name");
        List<String> constraintCols = List.of("public.customers.customer_id");
        List<String> partialCols = List.of("public.customers.phone");

        Map<String, Object> masked = dataMaskingService.maskDataWithRules("public.customers", inputRow, maskingCols, constraintCols, partialCols);

        assertNotNull(masked);
        assertNotEquals("John Doe", masked.get("name"));
        assertNotEquals(1001, masked.get("customer_id"));
        assertTrue(((Number) masked.get("customer_id")).intValue() > 0);
        assertTrue(((String) masked.get("phone")).endsWith("4567"));
    }

    @Test
    void testPaymentTableNumericAndIntegerDataTypes() {
        List<ColumnMetadata> paymentColumns = List.of(
                new ColumnMetadata("payment_id", "INTEGER", "NO", null, 32, 0),
                new ColumnMetadata("customer_id", "SMALLINT", "NO", null, 16, 0),
                new ColumnMetadata("staff_id", "INT2", "NO", null, 16, 0),
                new ColumnMetadata("rental_id", "INT4", "NO", null, 32, 0),
                new ColumnMetadata("amount", "NUMERIC", "NO", null, 5, 2),
                new ColumnMetadata("payment_date", "TIMESTAMP", "NO", null, null, null)
        );
        when(tableDiscoveryService.getTableColumnMetadata("payment")).thenReturn(paymentColumns);
        when(tableDiscoveryService.getTableColumnMetadata("tester.payment")).thenReturn(paymentColumns);

        Map<String, Object> paymentRow = new HashMap<>();
        paymentRow.put("payment_id", 17592);
        paymentRow.put("customer_id", 341);
        paymentRow.put("staff_id", 2);
        paymentRow.put("rental_id", 1520);
        paymentRow.put("amount", new java.math.BigDecimal("4.99"));
        paymentRow.put("payment_date", new java.sql.Timestamp(System.currentTimeMillis()));

        // Test 1: Constraint / FPH for all numeric and ID columns
        List<String> constraintCols = List.of(
                "tester.payment.payment_id",
                "tester.payment.customer_id",
                "tester.payment.staff_id",
                "tester.payment.rental_id",
                "tester.payment.amount"
        );
        Map<String, Object> maskedFph = dataMaskingService.maskDataWithRules("tester.payment", paymentRow, List.of(), constraintCols, List.of());

        // Check customer_id (int2 / smallint)
        Object fphCustomer = maskedFph.get("customer_id");
        assertNotNull(fphCustomer);
        assertTrue(fphCustomer instanceof Short, "customer_id must be java.lang.Short for int2/smallint");
        short custVal = (Short) fphCustomer;
        assertTrue(custVal > 0 && custVal <= 32767, "customer_id must be strictly positive within int2 range");

        // Check staff_id (int2 / smallint)
        Object fphStaff = maskedFph.get("staff_id");
        assertNotNull(fphStaff);
        assertTrue(fphStaff instanceof Short, "staff_id must be java.lang.Short for int2/smallint");

        // Check payment_id and rental_id (int4 / integer)
        Object fphPaymentId = maskedFph.get("payment_id");
        assertNotNull(fphPaymentId);
        assertTrue(fphPaymentId instanceof Integer);
        assertTrue(((Integer) fphPaymentId) > 0);

        // Check amount numeric(5, 2)
        Object fphAmount = maskedFph.get("amount");
        assertNotNull(fphAmount);
        assertTrue(fphAmount instanceof java.math.BigDecimal, "amount must be java.math.BigDecimal");
        java.math.BigDecimal bdFph = (java.math.BigDecimal) fphAmount;
        assertEquals(2, bdFph.scale(), "Scale must be 2");
        assertTrue(bdFph.compareTo(java.math.BigDecimal.ZERO) > 0, "amount must be strictly positive");
        assertTrue(bdFph.compareTo(new java.math.BigDecimal("1000")) < 0, "numeric(5, 2) must be strictly less than 10^3 (1000)");

        // Test 2: Masking / SFD for amount and customer_id
        List<String> sfdCols = List.of("tester.payment.amount", "tester.payment.customer_id");
        Map<String, Object> maskedSfd = dataMaskingService.maskDataWithRules("tester.payment", paymentRow, sfdCols, List.of(), List.of());

        Object sfdAmount = maskedSfd.get("amount");
        assertNotNull(sfdAmount);
        assertTrue(sfdAmount instanceof java.math.BigDecimal, "SFD amount must be java.math.BigDecimal");
        java.math.BigDecimal bdSfd = (java.math.BigDecimal) sfdAmount;
        assertEquals(2, bdSfd.scale(), "Scale must be 2");
        assertTrue(bdSfd.compareTo(java.math.BigDecimal.ZERO) > 0);
        assertTrue(bdSfd.compareTo(new java.math.BigDecimal("1000")) < 0, "SFD numeric(5, 2) must be less than 1000");

        Object sfdCustomer = maskedSfd.get("customer_id");
        assertTrue(sfdCustomer instanceof Short);
        assertTrue(((Short) sfdCustomer) > 0);
    }

    @Test
    void testForeignKeyDomainUnificationPreservesReferentialIntegrity() {
        // Parent table: customer (customer_id is INTEGER/int4)
        List<ColumnMetadata> customerCols = List.of(
                new ColumnMetadata("customer_id", "INTEGER", "NO", null, 32, 0),
                new ColumnMetadata("first_name", "VARCHAR", "NO", 45, null, null)
        );
        // Child table: payment (customer_id is SMALLINT/int2)
        List<ColumnMetadata> paymentCols = List.of(
                new ColumnMetadata("payment_id", "INTEGER", "NO", null, 32, 0),
                new ColumnMetadata("customer_id", "SMALLINT", "NO", null, 16, 0),
                new ColumnMetadata("amount", "NUMERIC", "NO", null, 5, 2)
        );

        when(tableDiscoveryService.getTableColumnMetadata("customer")).thenReturn(customerCols);
        when(tableDiscoveryService.getTableColumnMetadata("tester.customer")).thenReturn(customerCols);
        when(tableDiscoveryService.getTableColumnMetadata("payment")).thenReturn(paymentCols);
        when(tableDiscoveryService.getTableColumnMetadata("tester.payment")).thenReturn(paymentCols);

        // FK: payment.customer_id -> customer.customer_id
        ConstraintMetadata fkConstraint = new ConstraintMetadata(
                "payment_customer_id_fkey",
                "FOREIGN KEY",
                "tester.payment",
                "customer_id",
                "tester.customer",
                "customer_id"
        );
        when(tableDiscoveryService.getAllForeignKeys()).thenReturn(List.of(fkConstraint));
        dataMaskingService.resetForeignKeyRelationships();

        // Customer row with ID 341
        Map<String, Object> customerRow = new HashMap<>();
        customerRow.put("customer_id", 341);
        customerRow.put("first_name", "Peter");

        // Payment row referencing customer 341
        Map<String, Object> paymentRow = new HashMap<>();
        paymentRow.put("payment_id", 100);
        paymentRow.put("customer_id", (short) 341);
        paymentRow.put("amount", new java.math.BigDecimal("4.99"));

        List<String> constraintRules = List.of("tester.customer.customer_id", "tester.payment.customer_id");

        Map<String, Object> maskedCustomer = dataMaskingService.maskDataWithRules("tester.customer", customerRow, List.of(), constraintRules, List.of());
        Map<String, Object> maskedPayment = dataMaskingService.maskDataWithRules("tester.payment", paymentRow, List.of(), constraintRules, List.of());

        Object maskedCustIdInParent = maskedCustomer.get("customer_id");
        Object maskedCustIdInChild = maskedPayment.get("customer_id");

        assertNotNull(maskedCustIdInParent);
        assertNotNull(maskedCustIdInChild);

        // Both must be unified to Short (smallint)
        assertTrue(maskedCustIdInParent instanceof Short);
        assertTrue(maskedCustIdInChild instanceof Short);

        // The values MUST MATCH EXACTLY so foreign key constraint passes!
        assertEquals(maskedCustIdInParent, maskedCustIdInChild);
        assertTrue(((Short) maskedCustIdInParent) > 0 && ((Short) maskedCustIdInParent) <= 32767);
    }
}
