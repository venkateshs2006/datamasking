package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CsvMigrationConfig;
import com.enterprise.seedm.util.CsvUtils;
import com.github.javafaker.Faker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CsvMigrationServiceTest {

    private CsvMigrationService csvMigrationService;
    private FormatPreservingEncryptionService fpeService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MaskingConfigService maskingConfigService = new MaskingConfigService(List.of(), List.of(), List.of(), "DefaultSecretKey123");
        fpeService = new FormatPreservingEncryptionService(maskingConfigService);
        csvMigrationService = new CsvMigrationService(fpeService, new Faker());
    }

    @Test
    void testCsvMigrationEndToEndWithMaskingRules() throws Exception {
        Path srcDir = tempDir.resolve("csv-src");
        Path destDir = tempDir.resolve("csv-dest");
        Files.createDirectories(srcDir);
        Files.createDirectories(destDir);

        // Create sample CSV with header and 3 rows
        String csvContent = "id,name,email,card_number,notes\n" +
                "1,Alice Smith,alice@example.com,4111111111111111,Original note 1\n" +
                "2,Bob Jones,bob@example.com,4222222222222222,Original note 2\n" +
                "3,Charlie Brown,charlie@example.com,4333333333333333,Original note 3\n";
        Files.writeString(srcDir.resolve("customers.csv"), csvContent);

        CsvMigrationConfig config = new CsvMigrationConfig();

        CsvMigrationConfig.SourceConfig source = new CsvMigrationConfig.SourceConfig();
        source.setStorageType("local");
        source.setSourceDir(srcDir.toString());
        config.setSource(source);

        CsvMigrationConfig.DestConfig dest = new CsvMigrationConfig.DestConfig();
        dest.setStorageType("local");
        dest.setDestDir(destDir.toString());
        config.setDest(dest);

        CsvMigrationConfig.RulesConfig rules = new CsvMigrationConfig.RulesConfig();
        rules.setTargetTables(List.of("customers.csv"));

        // Setup SFD on name, PMD on email, FPE on card_number, NULL on notes
        rules.setMaskingColumns(List.of("name"));
        rules.setPartialMaskingColumns(List.of("email"));
        rules.setConstraintColumns(List.of("card_number"));
        rules.setNullifyColumns(List.of("notes"));
        rules.setMaskingKey("SecretFpeKey123");
        config.setRules(rules);

        String executionId = "csv-test-mig-1";
        csvMigrationService.processMigrationAsync(executionId, config);

        // Verify output file exists
        Path destFile = destDir.resolve("customers.csv");
        assertTrue(Files.exists(destFile), "Masked CSV file should be created in destination directory");

        List<String> lines = Files.readAllLines(destFile);
        assertEquals(4, lines.size(), "Should have header + 3 rows");
        assertEquals("id,name,email,card_number,notes", lines.get(0), "Header should match exactly");

        for (int i = 1; i < lines.size(); i++) {
            List<String> row = CsvUtils.parseLine(lines.get(i));
            assertEquals(5, row.size());

            // id should remain unchanged (1, 2, 3)
            assertEquals(String.valueOf(i), row.get(0));

            // name should be synthetic (not original)
            assertNotEquals("Alice Smith", row.get(1));
            assertNotEquals("Bob Jones", row.get(1));
            assertNotEquals("Charlie Brown", row.get(1));
            assertFalse(row.get(1).isBlank());

            // email should be pattern masked (e.g. contains asterisk or transformed)
            assertNotEquals("alice@example.com", row.get(2));
            assertNotEquals("bob@example.com", row.get(2));
            assertNotEquals("charlie@example.com", row.get(2));

            // card_number should be FPE masked (preserved length 16 digits)
            assertEquals(16, row.get(3).length(), "FPE should preserve 16 digit length");
            assertNotEquals("4111111111111111", row.get(3));

            // notes should be NULL or empty
            assertTrue(row.get(4).isEmpty() || "NULL".equalsIgnoreCase(row.get(4)));
        }

        // Verify progress reporting
        Map<String, Object> progress = csvMigrationService.getProgress(executionId);
        assertEquals("COMPLETED", progress.get("status"));
        assertEquals(3, ((Number) progress.get("recordsInserted")).intValue());
    }
}
