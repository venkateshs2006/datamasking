package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CsvSecureExportConfig;
import com.enterprise.seedm.model.CsvSecureImportConfig;
import com.enterprise.seedm.util.CsvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CsvSecureExportImportServiceTest {

    private CsvSecureExportService exportService;
    private CsvSecureImportService importService;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        MaskingConfigService maskingConfigService = new MaskingConfigService(List.of(), List.of(), List.of(), "DefaultSecretKey123");
        FormatPreservingEncryptionService fpeService = new FormatPreservingEncryptionService(maskingConfigService);
        CosConnectionService cosConnectionService = org.mockito.Mockito.mock(CosConnectionService.class);
        IbmCosService ibmCosService = new IbmCosService(cosConnectionService);
        exportService = new CsvSecureExportService(cosConnectionService, ibmCosService, fpeService, null, objectMapper);
        importService = new CsvSecureImportService(cosConnectionService, ibmCosService, null, objectMapper);
    }

    @Test
    void testCsvExportAndImportEndToEnd() throws Exception {
        Path srcDir = tempDir.resolve("csv-src");
        Path destExportDir = tempDir.resolve("csv-export");
        Path destImportDir = tempDir.resolve("csv-imported");
        Files.createDirectories(srcDir);
        Files.createDirectories(destExportDir);
        Files.createDirectories(destImportDir);

        // Create sample CSV file
        String sampleCsv = "id,name,email,score\n" +
                "101,John Doe,john@bnp.com,98.5\n" +
                "102,Jane Smith,jane@bnp.com,92.0\n";
        Files.writeString(srcDir.resolve("staff.csv"), sampleCsv);

        // 1. Scan source files
        CsvSecureExportConfig.StorageConfig sourceConfig = new CsvSecureExportConfig.StorageConfig();
        sourceConfig.setType("local");
        sourceConfig.setPath(srcDir.toString());

        Map<String, Object> scanRes = exportService.scanSourceFiles(sourceConfig);
        assertEquals("SUCCESS", scanRes.get("status"));
        assertEquals(1, scanRes.get("fileCount"));

        // 2. Sample CSV headers
        Map<String, Object> headersRes = exportService.sampleCsvHeaders(sourceConfig, "staff.csv");
        assertEquals("SUCCESS", headersRes.get("status"));
        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) headersRes.get("headers");
        assertTrue(headers.contains("name"));
        assertTrue(headers.contains("email"));
        assertTrue(headers.contains("score"));

        // 3. Run Secure Export
        String secretKey = "BnpCsvSecureKey2026!";
        CsvSecureExportConfig exportConfig = new CsvSecureExportConfig();
        exportConfig.setJobName("Test CSV Export");
        exportConfig.setSource(sourceConfig);

        CsvSecureExportConfig.StorageConfig destConfig = new CsvSecureExportConfig.StorageConfig();
        destConfig.setType("local");
        destConfig.setPath(destExportDir.toString());
        exportConfig.setDest(destConfig);

        CsvSecureExportConfig.RulesConfig rules = new CsvSecureExportConfig.RulesConfig();
        rules.setTargetFiles(List.of("staff.csv"));
        rules.setMaskingFields(Map.of("staff.csv", List.of("email")));
        rules.setMaskingKey(secretKey);
        exportConfig.setRules(rules);

        exportService.processCsvExport("csv-export-test-1", exportConfig);

        // Verify generated bundle file
        Path encFile = destExportDir.resolve("secure-csv-export.csv.enc");
        assertTrue(Files.exists(encFile), "Encrypted CSV export file should exist");
        assertTrue(Files.size(encFile) > 0);

        // 4. Validate Secret Key on import
        CsvSecureImportConfig importConfig = new CsvSecureImportConfig();
        importConfig.setJobName("Test CSV Import");

        CsvSecureImportConfig.StorageConfig importStorage = new CsvSecureImportConfig.StorageConfig();
        importStorage.setType("local");
        importStorage.setPath(destExportDir.toString());
        importStorage.setFileName("secure-csv-export.csv.enc");
        importConfig.setStorage(importStorage);

        CsvSecureImportConfig.DestinationConfig importDest = new CsvSecureImportConfig.DestinationConfig();
        importDest.setType("local");
        importDest.setPath(destImportDir.toString());
        importConfig.setDest(importDest);

        // Wrong key should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () ->
                importService.validateSecretKey(importConfig, "WrongPassword123!")
        );

        // Correct key should validate without exception
        assertDoesNotThrow(() ->
                importService.validateSecretKey(importConfig, secretKey)
        );

        // 5. Run Secure Import & Restore
        importService.processCsvImport("csv-import-test-1", importConfig, secretKey);

        // 6. Verify restored CSV
        Path restoredFile = destImportDir.resolve("staff.csv");
        assertTrue(Files.exists(restoredFile), "Restored CSV file should exist");

        List<String> restoredLines = Files.readAllLines(restoredFile);
        assertEquals(3, restoredLines.size(), "Should have header + 2 rows");
        assertEquals("id,name,email,score", restoredLines.get(0));

        List<String> row1 = CsvUtils.parseLine(restoredLines.get(1));
        assertEquals("101", row1.get(0));
        assertEquals("John Doe", row1.get(1));
        assertNotEquals("john@bnp.com", row1.get(2), "Email should be masked");
        assertEquals("98.5", row1.get(3));
    }

    @Test
    void testCsvExportMultiPartSplit() throws Exception {
        Path srcDir = tempDir.resolve("csv-split-src");
        Path destExportDir = tempDir.resolve("csv-split-export");
        Files.createDirectories(srcDir);
        Files.createDirectories(destExportDir);

        // Configure a small split size (e.g. 1KB) to force multi-part split
        exportService.setSplitFileSize("1KB");

        // Create 20 CSV files with some content
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder("col1,col2,col3,col4\n");
            for (int r = 0; r < 30; r++) {
                sb.append("data_").append(i).append("_").append(r).append(",some_text_field_content,another_column_data,12345\n");
            }
            Files.writeString(srcDir.resolve("file_" + i + ".csv"), sb.toString());
        }

        CsvSecureExportConfig exportConfig = new CsvSecureExportConfig();
        exportConfig.setJobName("MultiPart CSV Test");

        CsvSecureExportConfig.StorageConfig source = new CsvSecureExportConfig.StorageConfig();
        source.setType("local");
        source.setPath(srcDir.toString());
        exportConfig.setSource(source);

        CsvSecureExportConfig.StorageConfig dest = new CsvSecureExportConfig.StorageConfig();
        dest.setType("local");
        dest.setPath(destExportDir.toString());
        exportConfig.setDest(dest);

        String secretKey = "BnpSplitKey2026!";
        exportConfig.setFileEncryptionKey(secretKey);

        exportService.processCsvExport("csv-split-test", exportConfig);

        // Check that multi-part files were produced: secure-csv-export-part1.csv.enc, secure-csv-export-part2.csv.enc, etc.
        try (var stream = Files.list(destExportDir)) {
            List<Path> parts = stream.filter(p -> p.getFileName().toString().startsWith("secure-csv-export-part") && p.getFileName().toString().endsWith(".csv.enc"))
                    .toList();
            assertTrue(parts.size() > 1, "Should generate multiple split parts when exceeding threshold");
        }
    }
}
