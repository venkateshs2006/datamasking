package com.enterprise.seedm.service;

import com.enterprise.seedm.model.MongoSecureExportConfig;
import com.enterprise.seedm.model.MongoSecureImportConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.BsonBinaryWriter;
import org.bson.Document;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MongoSecureExportImportServiceTest {

    private MongoSecureExportService exportService;
    private MongoSecureImportService importService;
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
        exportService = new MongoSecureExportService(null, cosConnectionService, ibmCosService, fpeService, null, objectMapper, null);
        importService = new MongoSecureImportService(null, cosConnectionService, ibmCosService, null, objectMapper, null);
    }

    private void createSampleEncryptedBsonFile(Path targetFile, String saltKey) throws Exception {
        Path tempBson = tempDir.resolve("temp.bson");
        DocumentCodec codec = new DocumentCodec();

        try (OutputStream out = Files.newOutputStream(tempBson);
             DataOutputStream dataOut = new DataOutputStream(out)) {
            dataOut.write("MONGOBSON1\n".getBytes(StandardCharsets.UTF_8));

            // Collection start
            byte[] collBytes = "customers".getBytes(StandardCharsets.UTF_8);
            dataOut.writeByte(0x01);
            dataOut.writeShort(collBytes.length);
            dataOut.write(collBytes);

            // Document record
            Document doc = new Document("customerId", 1001).append("name", "Alice Smith").append("email", "alice@example.com");
            BasicOutputBuffer buf = new BasicOutputBuffer();
            codec.encode(new BsonBinaryWriter(buf), doc, EncoderContext.builder().build());
            byte[] bsonBytes = buf.toByteArray();

            dataOut.writeByte(0x02);
            dataOut.writeInt(bsonBytes.length);
            dataOut.write(bsonBytes);

            // Collection end
            dataOut.writeByte(0x03);

            // EOF
            dataOut.writeByte((byte) 0xFF);
            dataOut.flush();
        }

        exportService.encryptFileWithSalt(tempBson, targetFile, saltKey);
        Files.deleteIfExists(tempBson);
    }

    @Test
    void testValidateSecretKey_Success() throws Exception {
        String saltKey = "BnpMongoSecretSalt2026!";
        Path encFile = tempDir.resolve("secure-mongo-export.bson.enc");
        createSampleEncryptedBsonFile(encFile, saltKey);

        MongoSecureImportConfig config = new MongoSecureImportConfig();
        MongoSecureImportConfig.StorageConfig storage = new MongoSecureImportConfig.StorageConfig();
        storage.setType("local");
        storage.setPath(tempDir.toString());
        storage.setFileName("secure-mongo-export.bson.enc");
        config.setStorage(storage);

        assertDoesNotThrow(() -> importService.validateSecretKey(config, saltKey));
    }

    @Test
    void testValidateSecretKey_InvalidKey_ThrowsException() throws Exception {
        String correctKey = "CorrectSaltKey2026";
        String wrongKey = "WrongSaltKey9999";
        Path encFile = tempDir.resolve("secure-mongo-export.bson.enc");
        createSampleEncryptedBsonFile(encFile, correctKey);

        MongoSecureImportConfig config = new MongoSecureImportConfig();
        MongoSecureImportConfig.StorageConfig storage = new MongoSecureImportConfig.StorageConfig();
        storage.setType("local");
        storage.setPath(tempDir.toString());
        storage.setFileName("secure-mongo-export.bson.enc");
        config.setStorage(storage);

        assertThrows(IllegalArgumentException.class, () -> importService.validateSecretKey(config, wrongKey));
    }

    @Test
    void testScanStorage_DetectsBsonEncFile() throws Exception {
        String saltKey = "TestSalt2026";
        Path encFile = tempDir.resolve("backup-1.bson.enc");
        createSampleEncryptedBsonFile(encFile, saltKey);

        MongoSecureImportConfig.StorageConfig storage = new MongoSecureImportConfig.StorageConfig();
        storage.setType("local");
        storage.setPath(tempDir.toString());

        Map<String, Object> result = importService.scanStorage(storage);
        assertEquals("SUCCESS", result.get("status"));
        assertEquals(1, result.get("fileCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        assertNotNull(files);
        assertEquals(1, files.size());
        assertEquals("backup-1.bson.enc", files.get(0).get("name"));
        assertTrue((Boolean) files.get(0).get("encrypted"));
    }
}
