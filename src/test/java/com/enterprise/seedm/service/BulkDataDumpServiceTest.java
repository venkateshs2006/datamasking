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

        // Verify output file exists on disk
        File outFile = tempDir.resolve("customers.json").toFile();
        assertTrue(outFile.exists());

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
}
