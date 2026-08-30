package com.enterprise.seedm.service;

import com.enterprise.seedm.model.SecureExportConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SecureExportServiceTest {

    private SecureExportService secureExportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MaskingConfigService maskingConfigService = new MaskingConfigService(List.of(), List.of(), List.of(), "SaltKeyTest12345");
        FormatPreservingEncryptionService fpeService = new FormatPreservingEncryptionService(maskingConfigService);
        secureExportService = new SecureExportService(new ObjectMapper(), new Faker(), fpeService, maskingConfigService);
    }

    @Test
    void testEncryptFileWithSaltAndDecrypt() throws Exception {
        Path sourceSql = tempDir.resolve("sample-export.sql");
        String originalSql = "INSERT INTO users (id, name, email) VALUES (1, 'Alice', 'alice@bnp.com');\n" +
                "INSERT INTO users (id, name, email) VALUES (2, 'Bob', 'bob@bnp.com');\n";
        Files.writeString(sourceSql, originalSql);

        Path encryptedSql = tempDir.resolve("sample-export.sql.enc");
        String saltKey = "MySecretSaltKey123";

        // Encrypt
        secureExportService.encryptFileWithSalt(sourceSql, encryptedSql, saltKey);
        assertTrue(Files.exists(encryptedSql), "Encrypted file should exist");
        assertTrue(Files.size(encryptedSql) > 16, "Encrypted file should contain IV and ciphertext");

        // Verify we can decrypt with the same salt key
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(saltKey.getBytes(StandardCharsets.UTF_8));
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

        try (InputStream in = Files.newInputStream(encryptedSql)) {
            byte[] iv = new byte[16];
            int ivRead = in.read(iv);
            assertEquals(16, ivRead);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));

            byte[] cipherBytes = in.readAllBytes();
            byte[] decryptedBytes = cipher.doFinal(cipherBytes);
            String decryptedSql = new String(decryptedBytes, StandardCharsets.UTF_8);

            assertEquals(originalSql, decryptedSql, "Decrypted SQL content must match original SQL");
        }
    }
}
