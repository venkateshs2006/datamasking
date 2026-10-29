package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JsonSecureExportConfig;
import com.enterprise.seedm.model.JsonSecureImportConfig;
import com.enterprise.seedm.model.MongoSecureImportConfig;
import com.enterprise.seedm.model.SecureImportConfig;
import com.enterprise.seedm.util.BackupFileUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class BackupFileMultiPartTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("seedm.export.max-file-size");
    }

    @Test
    void testBackupFileUtilsNaturalSorting() {
        List<Path> files = List.of(
                tempDir.resolve("secure-export-part10.sql.enc"),
                tempDir.resolve("secure-export-part1.sql.enc"),
                tempDir.resolve("secure-export-part2.sql.enc"),
                tempDir.resolve("secure-export-part20.sql.enc"),
                tempDir.resolve("secure-export.sql.enc")
        );

        List<Path> sorted = BackupFileUtils.sortBackupFiles(files);

        assertEquals("secure-export.sql.enc", sorted.get(0).getFileName().toString());
        assertEquals("secure-export-part1.sql.enc", sorted.get(1).getFileName().toString());
        assertEquals("secure-export-part2.sql.enc", sorted.get(2).getFileName().toString());
        assertEquals("secure-export-part10.sql.enc", sorted.get(3).getFileName().toString());
        assertEquals("secure-export-part20.sql.enc", sorted.get(4).getFileName().toString());
    }

    @Test
    void testBackupFileUtilsEncryptionAndDecryptionAcrossFilesWithSameKey() throws Exception {
        String sharedKey = "BNPParibasUnifiedMasterKey2026!";

        Path plain1 = tempDir.resolve("chunk1.txt");
        Path plain2 = tempDir.resolve("chunk2.txt");
        Files.writeString(plain1, "First chunk of sensitive database records.");
        Files.writeString(plain2, "Second chunk of sensitive database records.");

        Path enc1 = tempDir.resolve("chunk1.enc");
        Path enc2 = tempDir.resolve("chunk2.enc");

        // Encrypt both files using the same key
        BackupFileUtils.encryptFileWithSalt(plain1, enc1, sharedKey);
        BackupFileUtils.encryptFileWithSalt(plain2, enc2, sharedKey);

        assertTrue(Files.exists(enc1));
        assertTrue(Files.exists(enc2));

        // Read and decrypt both files using createDecryptedInputStream
        try (InputStream in1 = BackupFileUtils.createDecryptedInputStream(enc1, sharedKey)) {
            String decrypted1 = new String(in1.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("First chunk of sensitive database records.", decrypted1);
        }

        try (InputStream in2 = BackupFileUtils.createDecryptedInputStream(enc2, sharedKey)) {
            String decrypted2 = new String(in2.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("Second chunk of sensitive database records.", decrypted2);
        }
    }

    @Test
    void testJsonExportAndImportMultiPartChunking() throws Exception {
        // Configure chunk size threshold to 250 bytes to force multi-part split
        System.setProperty("seedm.export.max-file-size", "250");
        assertEquals(250L, BackupFileUtils.getMaxFileSizeBytes());

        Path srcDir = tempDir.resolve("json-sources");
        Path exportDir = tempDir.resolve("json-export-parts");
        Path importDir = tempDir.resolve("json-import-dest");
        Files.createDirectories(srcDir);
        Files.createDirectories(exportDir);
        Files.createDirectories(importDir);

        // Create multiple source JSON files
        for (int i = 1; i <= 5; i++) {
            String jsonContent = "{\"id\": " + i + ", \"name\": \"User_" + i + "\", \"email\": \"user" + i + "@example.com\", \"role\": \"AUDITOR\"}";
            Files.writeString(srcDir.resolve("entity_" + i + ".json"), jsonContent);
        }

        CosConnectionService cosService = org.mockito.Mockito.mock(CosConnectionService.class);
        IbmCosService ibmCosService = new IbmCosService(cosService);
        MaskingConfigService maskingConfigService = new MaskingConfigService(List.of(), List.of(), List.of(), "DefaultKey123");
        FormatPreservingEncryptionService fpeService = new FormatPreservingEncryptionService(maskingConfigService);

        JsonSecureExportService exportService = new JsonSecureExportService(cosService, ibmCosService, fpeService, null, objectMapper);
        JsonSecureImportService importService = new JsonSecureImportService(cosService, ibmCosService, null, objectMapper);

        String encryptionKey = "SameSharedKeyForJsonChunks2026";

        JsonSecureExportConfig exportConfig = new JsonSecureExportConfig();
        exportConfig.setJobName("MultiPart JSON Export");

        JsonSecureExportConfig.StorageConfig sourceStorage = new JsonSecureExportConfig.StorageConfig();
        sourceStorage.setType("local");
        sourceStorage.setPath(srcDir.toString());
        exportConfig.setSource(sourceStorage);

        JsonSecureExportConfig.StorageConfig destStorage = new JsonSecureExportConfig.StorageConfig();
        destStorage.setType("local");
        destStorage.setPath(exportDir.toString());
        exportConfig.setDest(destStorage);

        JsonSecureExportConfig.RulesConfig rules = new JsonSecureExportConfig.RulesConfig();
        rules.setTargetFiles(List.of("entity_1.json", "entity_2.json", "entity_3.json", "entity_4.json", "entity_5.json"));
        rules.setMaskingKey(encryptionKey);
        exportConfig.setRules(rules);

        // Run export
        exportService.processJsonExport("multi-json-export-exec-1", exportConfig);

        // Verify multiple parts are created
        List<Path> exportedParts = BackupFileUtils.findBackupFiles(exportDir, "secure-json-export", ".json.enc");
        assertTrue(exportedParts.size() >= 2, "Should create at least 2 parts when max file size is 250 bytes, found: " + exportedParts.size());

        for (Path part : exportedParts) {
            assertTrue(part.getFileName().toString().contains("-part"), "File name should have part index: " + part.getFileName());
            assertTrue(Files.exists(part));
        }

        // Test Folder-based Import: validate secret key
        JsonSecureImportConfig importConfig = new JsonSecureImportConfig();
        JsonSecureImportConfig.StorageConfig importStorageConfig = new JsonSecureImportConfig.StorageConfig();
        importStorageConfig.setType("local");
        importStorageConfig.setPath(exportDir.toString()); // Give the folder name
        importConfig.setStorage(importStorageConfig);

        JsonSecureImportConfig.DestinationConfig importDestConfig = new JsonSecureImportConfig.DestinationConfig();
        importDestConfig.setType("local");
        importDestConfig.setPath(importDir.toString());
        importConfig.setDest(importDestConfig);

        // Verify resolveSourceFiles discovers all parts in sequential order
        List<Path> resolvedParts = importService.resolveSourceFiles(importConfig);
        assertEquals(exportedParts.size(), resolvedParts.size());
        for (int i = 0; i < resolvedParts.size(); i++) {
            assertEquals(exportedParts.get(i).getFileName(), resolvedParts.get(i).getFileName());
        }

        // Validate secret key succeeds
        assertDoesNotThrow(() -> importService.validateSecretKey(importConfig, encryptionKey));

        // Execute import from folder
        importService.processJsonImport("multi-json-import-exec-1", importConfig, encryptionKey);

        // Verify all 5 JSON files are extracted in importDir
        for (int i = 1; i <= 5; i++) {
            Path restoredFile = importDir.resolve("entity_" + i + ".json");
            assertTrue(Files.exists(restoredFile), "Restored file must exist: " + restoredFile);
            String content = Files.readString(restoredFile);
            assertTrue(content.contains("User_" + i));
        }
    }

    @Test
    void testMongoMultiPartExportAndImportSimulation() throws Exception {
        // Set small chunk size
        System.setProperty("seedm.export.max-file-size", "220");
        Path exportDir = tempDir.resolve("mongo-parts");
        Files.createDirectories(exportDir);

        String secretKey = "MongoMultiPartSecretKey2026!";

        // Create 4 simulated Mongo chunks manually with MongoSecureExportService rotator logic
        DocumentCodec codec = new DocumentCodec();

        Path unencTemp = tempDir.resolve("unenc-sim.bson");
        int partCount = 3;
        List<Path> generatedParts = new ArrayList<>();

        for (int p = 1; p <= partCount; p++) {
            try (DataOutputStream dataOut = new DataOutputStream(Files.newOutputStream(unencTemp))) {
                dataOut.write("MONGOBSON1\n".getBytes(StandardCharsets.UTF_8));
                byte[] collName = ("accounts_part_" + p).getBytes(StandardCharsets.UTF_8);
                dataOut.writeByte(0x01); // COLLECTION START
                dataOut.writeShort(collName.length);
                dataOut.write(collName);

                Document doc = new Document("accountNumber", "ACC-" + p * 100)
                        .append("balance", 5000 * p)
                        .append("holder", "Holder-" + p);
                BasicOutputBuffer buf = new BasicOutputBuffer();
                codec.encode(new BsonBinaryWriter(buf), doc, EncoderContext.builder().build());
                byte[] bsonBytes = buf.toByteArray();

                dataOut.writeByte(0x02); // DOC
                dataOut.writeInt(bsonBytes.length);
                dataOut.write(bsonBytes);

                dataOut.writeByte(0x03); // COLLECTION END
                dataOut.writeByte((byte) 0xFF); // EOF
            }

            Path partFile = exportDir.resolve("secure-mongo-export-part" + p + ".bson.enc");
            BackupFileUtils.encryptFileWithSalt(unencTemp, partFile, secretKey);
            generatedParts.add(partFile);
            Files.deleteIfExists(unencTemp);
        }

        // Test Folder-based Import with MongoSecureImportService
        MongoSecureImportConfig config = new MongoSecureImportConfig();
        MongoSecureImportConfig.StorageConfig storage = new MongoSecureImportConfig.StorageConfig();
        storage.setType("local");
        storage.setPath(exportDir.toString()); // Give folder name
        config.setStorage(storage);

        MongoSecureImportService mongoImportService = new MongoSecureImportService(null, null, null, null, objectMapper, null);
        List<Path> resolved = mongoImportService.resolveSourceFiles(config);
        assertEquals(3, resolved.size());
        assertEquals("secure-mongo-export-part1.bson.enc", resolved.get(0).getFileName().toString());
        assertEquals("secure-mongo-export-part2.bson.enc", resolved.get(1).getFileName().toString());
        assertEquals("secure-mongo-export-part3.bson.enc", resolved.get(2).getFileName().toString());

        // Validate secret key succeeds across the folder
        assertDoesNotThrow(() -> mongoImportService.validateSecretKey(config, secretKey));

        // Read through all parts and verify all docs are decoded
        List<String> holders = new ArrayList<>();
        for (Path part : resolved) {
            try (InputStream in = BackupFileUtils.createDecryptedInputStream(part, secretKey);
                 DataInputStream dataIn = new DataInputStream(in)) {
                byte[] header = new byte[11];
                dataIn.readFully(header);
                assertEquals("MONGOBSON1\n", new String(header, StandardCharsets.UTF_8));

                boolean reading = true;
                while (reading) {
                    int marker = dataIn.read();
                    if (marker == -1 || marker == (0xFF & 0xFF)) {
                        break;
                    }
                    if (marker == 0x01) {
                        int len = dataIn.readUnsignedShort();
                        byte[] name = new byte[len];
                        dataIn.readFully(name);
                    } else if (marker == 0x02) {
                        int len = dataIn.readInt();
                        byte[] bson = new byte[len];
                        dataIn.readFully(bson);
                        Document doc = codec.decode(new BsonBinaryReader(ByteBuffer.wrap(bson)), DecoderContext.builder().build());
                        holders.add(doc.getString("holder"));
                    }
                }
            }
        }

        assertEquals(List.of("Holder-1", "Holder-2", "Holder-3"), holders);
    }

    @Test
    void testSqlMultiPartExportAndFolderResolution() throws Exception {
        // Set small chunk size
        System.setProperty("seedm.export.max-file-size", "150");
        Path exportDir = tempDir.resolve("sql-parts");
        Files.createDirectories(exportDir);

        String secretKey = "SqlSharedMasterSecretKey2026!";

        // Create 3 SQL parts encrypted with the same key
        for (int p = 1; p <= 3; p++) {
            Path tempSql = tempDir.resolve("temp" + p + ".sql");
            String sqlContent = "-- Part " + p + "\n" +
                    "INSERT INTO transactions (id, amount, status) VALUES (" + p + ", " + (p * 100) + ", 'COMPLETED');\n";
            Files.writeString(tempSql, sqlContent);

            Path partFile = exportDir.resolve("secure-export-part" + p + ".sql.enc");
            BackupFileUtils.encryptFileWithSalt(tempSql, partFile, secretKey);
            Files.deleteIfExists(tempSql);
        }

        // Import service folder resolution
        SecureImportConfig config = new SecureImportConfig();
        SecureImportConfig.StorageConfig storage = new SecureImportConfig.StorageConfig();
        storage.setType("local");
        storage.setPath(exportDir.toString()); // Passing folder name
        config.setStorage(storage);

        SecureImportService importService = new SecureImportService(objectMapper);
        List<Path> resolved = importService.resolveSourceFiles(config);

        assertEquals(3, resolved.size());
        assertEquals("secure-export-part1.sql.enc", resolved.get(0).getFileName().toString());
        assertEquals("secure-export-part2.sql.enc", resolved.get(1).getFileName().toString());
        assertEquals("secure-export-part3.sql.enc", resolved.get(2).getFileName().toString());

        // Validate secret key succeeds
        assertDoesNotThrow(() -> importService.validateSecretKey(config, secretKey));

        // Decrypt all parts in sequence and verify concatenated SQL
        StringBuilder fullSql = new StringBuilder();
        for (Path part : resolved) {
            try (InputStream in = BackupFileUtils.createDecryptedInputStream(part, secretKey)) {
                fullSql.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        assertTrue(fullSql.toString().contains("INSERT INTO transactions (id, amount, status) VALUES (1, 100, 'COMPLETED');"));
        assertTrue(fullSql.toString().contains("INSERT INTO transactions (id, amount, status) VALUES (2, 200, 'COMPLETED');"));
        assertTrue(fullSql.toString().contains("INSERT INTO transactions (id, amount, status) VALUES (3, 300, 'COMPLETED');"));
    }
}
