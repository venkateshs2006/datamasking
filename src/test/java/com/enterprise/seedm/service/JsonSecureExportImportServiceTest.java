package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JsonSecureExportConfig;
import com.enterprise.seedm.model.JsonSecureImportConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JsonSecureExportImportServiceTest {

    private JsonSecureExportService exportService;
    private JsonSecureImportService importService;
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
        exportService = new JsonSecureExportService(cosConnectionService, ibmCosService, fpeService, null, objectMapper);
        importService = new JsonSecureImportService(cosConnectionService, ibmCosService, null, objectMapper);
    }

    @Test
    void testJsonExportAndImportEndToEnd() throws Exception {
        Path srcDir = tempDir.resolve("json-src");
        Path destExportDir = tempDir.resolve("json-export");
        Path destImportDir = tempDir.resolve("json-imported");
        Files.createDirectories(srcDir);
        Files.createDirectories(destExportDir);
        Files.createDirectories(destImportDir);

        // Create sample json file
        String sampleJson = "{\"userId\": 501, \"username\": \"john_doe\", \"email\": \"john@bnp.com\", \"profile\": {\"address\": \"123 Bank St\"}}";
        Files.writeString(srcDir.resolve("users.json"), sampleJson);

        // 1. Scan source files
        JsonSecureExportConfig.StorageConfig sourceConfig = new JsonSecureExportConfig.StorageConfig();
        sourceConfig.setType("local");
        sourceConfig.setPath(srcDir.toString());

        Map<String, Object> scanRes = exportService.scanSourceFiles(sourceConfig);
        assertEquals("SUCCESS", scanRes.get("status"));
        assertEquals(1, scanRes.get("fileCount"));

        // 2. Sample JSON fields
        Map<String, Object> fieldsRes = exportService.sampleJsonFields(sourceConfig, "users.json");
        assertEquals("SUCCESS", fieldsRes.get("status"));
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) fieldsRes.get("fields");
        assertTrue(fields.contains("email"));
        assertTrue(fields.contains("profile.address"));

        // 3. Run Secure Export
        String secretKey = "BnpSecureSalt2026!";
        JsonSecureExportConfig exportConfig = new JsonSecureExportConfig();
        exportConfig.setJobName("Test JSON Export");
        exportConfig.setSource(sourceConfig);

        JsonSecureExportConfig.StorageConfig destConfig = new JsonSecureExportConfig.StorageConfig();
        destConfig.setType("local");
        destConfig.setPath(destExportDir.toString());
        exportConfig.setDest(destConfig);

        JsonSecureExportConfig.RulesConfig rules = new JsonSecureExportConfig.RulesConfig();
        rules.setTargetFiles(List.of("users.json"));
        rules.setMaskingFields(Map.of("users.json", List.of("email", "profile.address")));
        rules.setMaskingKey(secretKey);
        exportConfig.setRules(rules);

        exportService.processJsonExport("json-export-test-1", exportConfig);

        Path encFile = destExportDir.resolve("secure-json-export.json.enc");
        assertTrue(Files.exists(encFile));
        assertTrue(Files.size(encFile) > 0);

        // 4. Validate Secret Key on import
        JsonSecureImportConfig importConfig = new JsonSecureImportConfig();
        importConfig.setJobName("Test JSON Import");

        JsonSecureImportConfig.StorageConfig importStorage = new JsonSecureImportConfig.StorageConfig();
        importStorage.setType("local");
        importStorage.setPath(destExportDir.toString());
        importStorage.setFileName("secure-json-export.json.enc");
        importConfig.setStorage(importStorage);

        JsonSecureImportConfig.DestinationConfig importDest = new JsonSecureImportConfig.DestinationConfig();
        importDest.setType("local");
        importDest.setPath(destImportDir.toString());
        importDest.setOverwrite(true);
        importConfig.setDest(importDest);

        assertDoesNotThrow(() -> importService.validateSecretKey(importConfig, secretKey));
        assertThrows(IllegalArgumentException.class, () -> importService.validateSecretKey(importConfig, "WrongKey123"));

        // 5. Process Import
        importService.processJsonImport("json-import-test-1", importConfig, secretKey);

        Path restoredFile = destImportDir.resolve("users.json");
        assertTrue(Files.exists(restoredFile));

        JsonNode restoredJson = objectMapper.readTree(restoredFile.toFile());
        assertEquals(501, restoredJson.get("userId").asInt());
        assertNotEquals("john@bnp.com", restoredJson.get("email").asText());
        assertNotNull(restoredJson.get("profile"));
        assertNotEquals("123 Bank St", restoredJson.get("profile").get("address").asText());
    }

    @Test
    void testJsonExportWithSfdPmdFphRules() throws Exception {
        Path srcDir = tempDir.resolve("json-sfd-src");
        Path destExportDir = tempDir.resolve("json-sfd-export");
        Path destImportDir = tempDir.resolve("json-sfd-imported");
        Files.createDirectories(srcDir);
        Files.createDirectories(destExportDir);
        Files.createDirectories(destImportDir);

        String sampleJson = "{\"name\": \"Alice Wonderland\", \"phone\": \"9876543210\", \"ssn\": \"123456789\"}";
        Files.writeString(srcDir.resolve("accounts.json"), sampleJson);

        JsonSecureExportConfig.StorageConfig sourceConfig = new JsonSecureExportConfig.StorageConfig();
        sourceConfig.setType("local");
        sourceConfig.setPath(srcDir.toString());

        JsonSecureExportConfig.StorageConfig destConfig = new JsonSecureExportConfig.StorageConfig();
        destConfig.setType("local");
        destConfig.setPath(destExportDir.toString());

        String secretKey = "Custom16CharSalt";
        JsonSecureExportConfig exportConfig = new JsonSecureExportConfig();
        exportConfig.setJobName("Test SFD PMD FPH");
        exportConfig.setSource(sourceConfig);
        exportConfig.setDest(destConfig);

        JsonSecureExportConfig.RulesConfig rules = new JsonSecureExportConfig.RulesConfig();
        rules.setTargetFiles(List.of("accounts.json"));
        rules.setMaskingColumns(List.of("accounts.json.name")); // SFD
        rules.setPartialMaskingColumns(List.of("accounts.json.phone")); // PMD
        rules.setConstraintColumns(List.of("accounts.json.ssn")); // FPH
        rules.setMaskingKey(secretKey);
        exportConfig.setRules(rules);

        exportService.processJsonExport("sfd-pmd-fph-test", exportConfig);

        JsonSecureImportConfig importConfig = new JsonSecureImportConfig();
        JsonSecureImportConfig.StorageConfig importStorage = new JsonSecureImportConfig.StorageConfig();
        importStorage.setType("local");
        importStorage.setPath(destExportDir.toString());
        importStorage.setFileName("secure-json-export.json.enc");
        importConfig.setStorage(importStorage);

        JsonSecureImportConfig.DestinationConfig importDest = new JsonSecureImportConfig.DestinationConfig();
        importDest.setType("local");
        importDest.setPath(destImportDir.toString());
        importDest.setOverwrite(true);
        importConfig.setDest(importDest);

        importService.processJsonImport("sfd-pmd-fph-import", importConfig, secretKey);

        Path restoredFile = destImportDir.resolve("accounts.json");
        assertTrue(Files.exists(restoredFile));

        JsonNode json = objectMapper.readTree(restoredFile.toFile());
        assertNotEquals("Alice Wonderland", json.get("name").asText()); // SFD
        assertEquals("****3210", json.get("phone").asText()); // PMD keeps last 4
        assertNotEquals("123456789", json.get("ssn").asText()); // FPH
    }
}
