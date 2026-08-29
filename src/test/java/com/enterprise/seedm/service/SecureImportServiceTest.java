package com.enterprise.seedm.service;

import com.enterprise.seedm.model.SecureImportConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SecureImportServiceTest {

    private SecureImportService secureImportService;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        secureImportService = new SecureImportService(objectMapper);
    }

    private void createEncryptedSqlFile(Path targetFile, String saltKey, String sqlContent) throws Exception {
        Path tempSql = tempDir.resolve("temp.sql");
        Files.writeString(tempSql, sqlContent, StandardCharsets.UTF_8);

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(saltKey.trim().getBytes(StandardCharsets.UTF_8));
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        try (InputStream in = Files.newInputStream(tempSql);
             OutputStream out = Files.newOutputStream(targetFile)) {
            out.write(iv);
            try (CipherOutputStream cipherOut = new CipherOutputStream(out, cipher)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    cipherOut.write(buffer, 0, bytesRead);
                }
                cipherOut.flush();
            }
        }
        Files.deleteIfExists(tempSql);
    }

    @Test
    void testValidateSecretKey_Success() throws Exception {
        String saltKey = "SecretKey1234567";
        String sampleSql = "-- CREATE TABLE test_users\n" +
                "CREATE TABLE test_users (id INT PRIMARY KEY, name VARCHAR(100));\n" +
                "INSERT INTO test_users (id, name) VALUES (1, 'Alice');\n";

        Path encFile = tempDir.resolve("secure-export.sql.enc");
        createEncryptedSqlFile(encFile, saltKey, sampleSql);

        SecureImportConfig config = new SecureImportConfig();
        SecureImportConfig.StorageConfig storage = new SecureImportConfig.StorageConfig();
        storage.setType("local");
        storage.setPath(tempDir.toString());
        config.setStorage(storage);

        assertDoesNotThrow(() -> secureImportService.validateSecretKey(config, saltKey));
    }

    @Test
    void testValidateSecretKey_InvalidKey_ThrowsException() throws Exception {
        String correctKey = "CorrectKey123456";
        String wrongKey = "WrongKey99999999";
        String sampleSql = "-- CREATE TABLE test_users\nCREATE TABLE test_users (id INT);\n";

        Path encFile = tempDir.resolve("secure-export.sql.enc");
        createEncryptedSqlFile(encFile, correctKey, sampleSql);

        SecureImportConfig config = new SecureImportConfig();
        SecureImportConfig.StorageConfig storage = new SecureImportConfig.StorageConfig();
        storage.setType("local");
        storage.setPath(tempDir.toString());
        config.setStorage(storage);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            secureImportService.validateSecretKey(config, wrongKey);
        });

        assertTrue(ex.getMessage().contains("Invalid secret key"));
    }

    @Test
    void testScanStorage_FindsEncryptedAndPlainSql() throws Exception {
        Path encFile = tempDir.resolve("backup.sql.enc");
        Files.writeString(encFile, "dummy encrypted bytes");

        Path plainFile = tempDir.resolve("schema.sql");
        Files.writeString(plainFile, "-- CREATE TABLE dummy (id INT);");

        Map<String, Object> request = Map.of(
                "type", "local",
                "path", tempDir.toString()
        );

        Map<String, Object> result = secureImportService.scanStorage(request);
        assertEquals("SUCCESS", result.get("status"));
        assertEquals(2, result.get("fileCount"));
    }
}
