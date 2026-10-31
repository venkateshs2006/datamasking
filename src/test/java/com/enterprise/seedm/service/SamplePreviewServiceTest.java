package com.enterprise.seedm.service;

import com.enterprise.seedm.model.SamplePreviewRequest;
import com.enterprise.seedm.model.SamplePreviewResponse;
import com.enterprise.seedm.model.AiPiiDetectionResponse.PiiEntityInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class SamplePreviewServiceTest {

    @Mock
    private TableDiscoveryService tableDiscoveryService;

    @Mock
    private DataMaskingService dataMaskingService;

    @Mock
    private AiPiiDetectorService aiPiiDetectorService;

    private SamplePreviewService samplePreviewService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        samplePreviewService = new SamplePreviewService(tableDiscoveryService, dataMaskingService, aiPiiDetectorService);
    }

    @Test
    void testGeneratePreviewRedactsPiiInBeforeAndAppliesMaskingInAfter() {
        String table = "customer";
        List<String> columns = List.of("customer_id", "first_name", "email", "status");

        when(tableDiscoveryService.getTableColumns(table)).thenReturn(columns);
        when(tableDiscoveryService.getSampleRows(eq(table), anyInt())).thenReturn(List.of(
                Map.of("customer_id", 101, "first_name", "Alice", "email", "alice@secretcorp.com", "status", "ACTIVE")
        ));

        when(dataMaskingService.maskDataWithRules(eq(table), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of(
                        "customer_id", 101,
                        "first_name", "FakeFirstName",
                        "email", "synthetic@faker.org",
                        "status", "ACTIVE"
                ));

        SamplePreviewRequest request = SamplePreviewRequest.builder()
                .tableName(table)
                .maskingColumns(List.of("customer.first_name", "customer.email"))
                .build();

        SamplePreviewResponse response = samplePreviewService.generatePreview(request);

        assertNotNull(response);
        assertEquals(table, response.getTableName());
        assertEquals(4, response.getColumns().size());
        assertEquals(1, response.getRowCount());

        // Verify BEFORE row redacts sensitive PII
        Map<String, Object> beforeRow = response.getBeforeRows().get(0);
        assertNotNull(beforeRow);
        assertEquals(101, beforeRow.get("customer_id"));
        assertEquals("ACTIVE", beforeRow.get("status"));
        // Real PII values must NEVER be exposed
        assertNotEquals("Alice", beforeRow.get("first_name"));
        assertNotEquals("alice@secretcorp.com", beforeRow.get("email"));
        assertTrue(beforeRow.get("first_name").toString().contains("PROTECTED"));
        assertTrue(beforeRow.get("email").toString().contains("PROTECTED"));

        // Verify AFTER row shows synthetic masked data
        Map<String, Object> afterRow = response.getAfterRows().get(0);
        assertNotNull(afterRow);
        assertEquals("FakeFirstName", afterRow.get("first_name"));
        assertEquals("synthetic@faker.org", afterRow.get("email"));
        assertEquals("ACTIVE", afterRow.get("status"));
    }

    @Test
    void testGeneratePreviewWithSyntheticFallbackWhenTableIsEmpty() {
        String table = "empty_table";
        when(tableDiscoveryService.getTableColumns(table)).thenReturn(List.of("id", "email", "amount"));
        when(tableDiscoveryService.getSampleRows(eq(table), anyInt())).thenReturn(List.of());

        when(dataMaskingService.maskDataWithRules(eq(table), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("id", 1001, "email", "masked@fake.com", "amount", 123.45));

        SamplePreviewRequest request = SamplePreviewRequest.builder()
                .tableName(table)
                .maskingColumns(List.of("empty_table.email"))
                .limit(3)
                .build();

        SamplePreviewResponse response = samplePreviewService.generatePreview(request);

        assertNotNull(response);
        assertEquals(3, response.getRowCount());
        assertTrue(response.getBeforeRows().get(0).get("email").toString().contains("PROTECTED"));
        assertEquals("masked@fake.com", response.getAfterRows().get(0).get("email"));
    }

    @Test
    void testGeneratePreviewForCsvFileDoesNotQueryTableDiscovery() {
        String csvFileName = "sample_employee_data.csv";
        when(dataMaskingService.maskDataWithRules(eq(csvFileName), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("id", 1001, "name", "Masked User", "email", "masked@fake.com", "created_at", "2026-01-01"));

        SamplePreviewRequest request = SamplePreviewRequest.builder()
                .tableName(csvFileName)
                .jobType("csv")
                .columns(List.of("id", "name", "email", "created_at"))
                .maskingColumns(List.of("sample_employee_data.csv.email"))
                .limit(5)
                .build();

        SamplePreviewResponse response = samplePreviewService.generatePreview(request);

        assertNotNull(response);
        assertEquals(csvFileName, response.getTableName());
        assertEquals(4, response.getColumns().size());
        assertEquals(5, response.getRowCount());

        // Crucial verification: JDBC table discovery MUST NEVER be called for CSV files
        org.mockito.Mockito.verify(tableDiscoveryService, org.mockito.Mockito.never()).getTableColumns(anyString());
        org.mockito.Mockito.verify(tableDiscoveryService, org.mockito.Mockito.never()).getSampleRows(anyString(), anyInt());
    }

    @Test
    void testGeneratePreviewForCsvFileWithLocalFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        String fileName = "employees.csv";
        java.nio.file.Path csvFile = tempDir.resolve(fileName);
        java.nio.file.Files.writeString(csvFile, "emp_id,full_name,email_addr,department\n101,John Smith,john@company.com,Finance\n102,Jane Doe,jane@company.com,Engineering\n");

        when(dataMaskingService.maskDataWithRules(eq(fileName), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("emp_id", "101", "full_name", "Masked User", "email_addr", "masked@fake.com", "department", "Finance"));

        SamplePreviewRequest request = SamplePreviewRequest.builder()
                .tableName(fileName)
                .jobType("csv")
                .sourcePath(tempDir.toString())
                .maskingColumns(List.of("employees.csv.email_addr"))
                .limit(5)
                .build();

        SamplePreviewResponse response = samplePreviewService.generatePreview(request);

        assertNotNull(response);
        assertEquals(fileName, response.getTableName());
        assertEquals(List.of("emp_id", "full_name", "email_addr", "department"), response.getColumns());
        assertEquals(2, response.getRowCount());

        // Zero exposure for sensitive email
        assertTrue(response.getBeforeRows().get(0).get("email_addr").toString().contains("PROTECTED"));
        assertEquals("Finance", response.getBeforeRows().get(0).get("department"));

        org.mockito.Mockito.verify(tableDiscoveryService, org.mockito.Mockito.never()).getTableColumns(anyString());
        org.mockito.Mockito.verify(tableDiscoveryService, org.mockito.Mockito.never()).getSampleRows(anyString(), anyInt());
    }
}
