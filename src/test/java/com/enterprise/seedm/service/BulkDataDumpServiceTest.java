package com.enterprise.seedm.service;

import com.enterprise.seedm.model.BulkDumpConfig;
import com.enterprise.seedm.model.BulkDumpProgress;
import com.enterprise.seedm.model.BulkDumpTableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BulkDataDumpServiceTest {

    private DbConnectionService dbConnectionService;
    private CosConnectionService cosConnectionService;
    private MongoConnectionHelper mongoConnectionHelper;
    private ObjectMapper objectMapper;
    private IbmCosService ibmCosService;
    private BulkDataDumpService bulkDataDumpService;

    @BeforeEach
    void setUp() {
        dbConnectionService = Mockito.mock(DbConnectionService.class);
        cosConnectionService = Mockito.mock(CosConnectionService.class);
        mongoConnectionHelper = Mockito.mock(MongoConnectionHelper.class);
        objectMapper = new ObjectMapper();
        ibmCosService = Mockito.mock(IbmCosService.class);

        bulkDataDumpService = new BulkDataDumpService(
                dbConnectionService,
                cosConnectionService,
                mongoConnectionHelper,
                objectMapper,
                ibmCosService
        );
    }

    @Test
    void testDiscoverJsonTablesWithCounts(@TempDir Path tempDir) throws Exception {
        // Create sample JSON files in temp dir
        File f1 = tempDir.resolve("users.json").toFile();
        objectMapper.writeValue(f1, List.of(
                Map.of("id", 1, "name", "Alice", "email", "alice@example.com"),
                Map.of("id", 2, "name", "Bob", "email", "bob@example.com")
        ));

        List<BulkDumpTableConfig> tables = bulkDataDumpService.discoverTablesWithCounts(
                "json", null, null, tempDir.toString());

        assertNotNull(tables);
        assertEquals(1, tables.size());
        assertEquals("users.json", tables.get(0).getTableName());
        assertEquals(2, tables.get(0).getCurrentCount());
        assertTrue(tables.get(0).getColumnCount() >= 2);
    }

    @Test
    void testExecuteJsonBulkDumpAndSampleReport(@TempDir Path tempDir) throws Exception {
        String executionId = bulkDataDumpService.generateExecutionId();
        assertNotNull(executionId);
        assertTrue(executionId.startsWith("bulk-dump-"));

        BulkDumpConfig config = BulkDumpConfig.builder()
                .dbType("json")
                .dirPath(tempDir.toString())
                .jobName("Test Bulk Dump")
                .department("Engineering")
                .tables(List.of(
                        BulkDumpTableConfig.builder()
                                .tableName("customers.json")
                                .targetCount(25)
                                .columns(List.of("id", "fullName", "email", "phone", "department", "status"))
                                .build()
                ))
                .build();

        BulkDumpProgress progress = bulkDataDumpService.startBulkDump(executionId, 101L, config);
        assertNotNull(progress);
        assertEquals("RUNNING", progress.getStatus());

        // Wait for asynchronous dump to finish
        int attempts = 0;
        while (attempts < 50 && !"COMPLETED".equals(progress.getStatus()) && !"FAILED".equals(progress.getStatus())) {
            Thread.sleep(100);
            progress = bulkDataDumpService.getProgress(executionId);
            attempts++;
        }

        assertEquals("COMPLETED", progress.getStatus());
        assertEquals(1, progress.getProcessedTables());
        assertEquals(25, progress.getTotalInsertedRecords());
        assertEquals(100, progress.getOverallPercent());

        // Verify output files exist on disk (25 individual JSON files generated instead of a single array file)
        File firstFile = tempDir.resolve("customers_1.json").toFile();
        File lastFile = tempDir.resolve("customers_25.json").toFile();
        assertTrue(firstFile.exists(), "customers_1.json should exist");
        assertTrue(lastFile.exists(), "customers_25.json should exist");

        // Verify file structure is a single JSON object (not an array of 25 records)
        com.fasterxml.jackson.databind.JsonNode firstNode = objectMapper.readTree(firstFile);
        assertTrue(firstNode.isObject(), "Generated file must be a JSON object");
        assertFalse(firstNode.isArray(), "Generated file must not be an array of records");
        assertEquals(1L, firstNode.get("id").asLong());

        // Verify total files generated in directory equals target count (25)
        try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(tempDir)) {
            long jsonCount = stream.filter(p -> p.getFileName().toString().endsWith(".json")).count();
            assertEquals(25L, jsonCount, "Must generate exactly 25 files");
        }

        // Verify sample data report contains rows
        Map<String, List<Map<String, Object>>> sampleReport = progress.getSampleDataReport();
        assertNotNull(sampleReport);
        assertTrue(sampleReport.containsKey("customers.json"));
        List<Map<String, Object>> samples = sampleReport.get("customers.json");
        assertFalse(samples.isEmpty());
        assertTrue(samples.size() <= 5);

        // Verify sample record has required fields populated
        Map<String, Object> firstSample = samples.get(0);
        assertTrue(firstSample.containsKey("email"));
        assertTrue(firstSample.containsKey("fullName"));
        assertNotNull(firstSample.get("email"));
    }

    @Test
    void testExecuteJsonBulkDumpWithTemplateStructure(@TempDir Path tempDir) throws Exception {
        // Create an existing template JSON file with nested structure
        File templateFile = tempDir.resolve("account_profile.json").toFile();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(templateFile, Map.of(
                "id", 99,
                "profile", Map.of(
                        "fullName", "Template User",
                        "email", "template@corp.com"
                ),
                "status", "ACTIVE"
        ));

        String executionId = bulkDataDumpService.generateExecutionId();
        BulkDumpConfig config = BulkDumpConfig.builder()
                .dbType("json")
                .dirPath(tempDir.toString())
                .jobName("Template Structure Test")
                .tables(List.of(
                        BulkDumpTableConfig.builder()
                                .tableName("account_profile.json")
                                .targetCount(5)
                                .build()
                ))
                .build();

        bulkDataDumpService.startBulkDump(executionId, 102L, config);

        int attempts = 0;
        BulkDumpProgress progress = null;
        while (attempts < 50) {
            Thread.sleep(100);
            progress = bulkDataDumpService.getProgress(executionId);
            if (progress != null && ("COMPLETED".equals(progress.getStatus()) || "FAILED".equals(progress.getStatus()))) {
                break;
            }
            attempts++;
        }

        assertNotNull(progress);
        assertEquals("COMPLETED", progress.getStatus());

        // Verify account_profile_1.json through account_profile_5.json were created with nested structure
        for (int i = 1; i <= 5; i++) {
            File genFile = tempDir.resolve("account_profile_" + i + ".json").toFile();
            assertTrue(genFile.exists(), "File should exist: " + genFile.getName());
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(genFile);
            assertTrue(node.isObject(), "Must be JSON object");
            assertTrue(node.has("profile"), "Must preserve nested 'profile' object from template");
            assertTrue(node.get("profile").isObject());
            assertTrue(node.get("profile").has("email"));
            assertEquals(i, node.get("id").asLong());
        }
    }

    @Test
    void testDiscoverCsvTablesWithCounts(@TempDir Path tempDir) throws Exception {
        // Create sample CSV file
        java.nio.file.Files.writeString(
                tempDir.resolve("employees.csv"),
                "id,name,role,salary,department\n" +
                "1,Alice,Engineer,95000,IT\n" +
                "2,Bob,Analyst,85000,Finance\n" +
                "3,Charlie,Manager,115000,Operations\n"
        );

        List<BulkDumpTableConfig> tables = bulkDataDumpService.discoverTablesWithCounts(
                "csv", null, null, tempDir.toString());

        assertNotNull(tables);
        assertEquals(1, tables.size());
        assertEquals("employees.csv", tables.get(0).getTableName());
        assertEquals(3, tables.get(0).getCurrentCount());
        assertEquals(5, tables.get(0).getColumnCount());
        assertTrue(tables.get(0).getColumns().contains("salary"));
    }

    @Test
    void testExecuteCsvBulkDumpSingleFileWithAllRows(@TempDir Path tempDir) throws Exception {
        // Create template CSV file
        java.nio.file.Files.writeString(
                tempDir.resolve("transactions.csv"),
                "txn_id,account_id,amount,status,notes\n" +
                "1001,ACC-1,250.00,SUCCESS,Initial transfer\n"
        );

        String executionId = bulkDataDumpService.generateExecutionId();
        BulkDumpConfig config = BulkDumpConfig.builder()
                .dbType("csv")
                .dirPath(tempDir.toString())
                .jobName("CSV Bulk Dump Single File Test")
                .tables(List.of(
                        BulkDumpTableConfig.builder()
                                .tableName("transactions.csv")
                                .targetCount(25)
                                .build()
                ))
                .build();

        bulkDataDumpService.startBulkDump(executionId, 103L, config);

        int attempts = 0;
        BulkDumpProgress progress = null;
        while (attempts < 50) {
            Thread.sleep(100);
            progress = bulkDataDumpService.getProgress(executionId);
            if (progress != null && ("COMPLETED".equals(progress.getStatus()) || "FAILED".equals(progress.getStatus()))) {
                break;
            }
            attempts++;
        }

        assertNotNull(progress);
        assertEquals("COMPLETED", progress.getStatus());
        assertEquals(25, progress.getTotalInsertedRecords());

        // Verify that exactly 1 CSV file exists with all 25 rows (not 25 separate files)
        Path outputFile = tempDir.resolve("transactions.csv");
        assertTrue(java.nio.file.Files.exists(outputFile), "Single transactions.csv must exist");
        List<String> lines = java.nio.file.Files.readAllLines(outputFile);
        assertEquals(26, lines.size(), "Single CSV file must contain 1 header line + 25 data rows = 26 lines");
        assertEquals("txn_id,account_id,amount,status,notes", lines.get(0), "Header line must match template columns");

        // Verify first and last data rows
        List<String> firstRow = com.enterprise.seedm.util.CsvUtils.parseLine(lines.get(1));
        assertEquals(5, firstRow.size());
        assertEquals("1", firstRow.get(0));

        List<String> lastRow = com.enterprise.seedm.util.CsvUtils.parseLine(lines.get(25));
        assertEquals(5, lastRow.size());
        assertEquals("25", lastRow.get(0));

        // Verify no split/numbered files were created
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("transactions_1.csv")), "Should not create transactions_1.csv");
    }
}
