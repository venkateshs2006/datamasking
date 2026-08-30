package com.enterprise.seedm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class FormatPreservingEncryptionServiceTest {

    private FormatPreservingEncryptionService fpeService;
    private MaskingConfigService maskingConfigService;

    @BeforeEach
    void setUp() {
        maskingConfigService = new MaskingConfigService(List.of(), List.of(), List.of(), "StandardSaltKey16");
        fpeService = new FormatPreservingEncryptionService(maskingConfigService);
    }

    @Test
    void testEncryptStringPreservesLength() {
        String input = "HelloWorld123";
        Object encrypted = fpeService.encrypt(input, "varchar");

        assertNotNull(encrypted);
        assertEquals(input.length(), encrypted.toString().length(), "Encrypted string must preserve character length");
        assertNotEquals(input, encrypted.toString(), "Encrypted string must differ from original");

        // Determinism test with same salt
        Object encryptedAgain = fpeService.encrypt(input, "varchar");
        assertEquals(encrypted, encryptedAgain, "Same salt key must produce deterministic encrypted output");
    }

    @Test
    void testEncryptIntAndLong() {
        Integer inputInt = 45678;
        Object encryptedInt = fpeService.encrypt(inputInt, "integer");
        assertNotNull(encryptedInt);
        assertTrue(encryptedInt instanceof Integer);

        Long inputLong = 9876543210L;
        Object encryptedLong = fpeService.encrypt(inputLong, "bigint");
        assertNotNull(encryptedLong);
        assertTrue(encryptedLong instanceof Long);
    }

    @Test
    void testEncryptUUID() {
        String randomUuid = UUID.randomUUID().toString();
        Object encryptedUuid = fpeService.encrypt(randomUuid, "uuid");

        assertNotNull(encryptedUuid);
        assertEquals(36, encryptedUuid.toString().length());
        assertDoesNotThrow(() -> UUID.fromString(encryptedUuid.toString()));
    }

    @Test
    void testEncryptWithCustomSalt() {
        String input = "ConfidentialData";
        Object result1 = fpeService.encrypt(input, "varchar", "SaltAlpha12345678");
        Object result2 = fpeService.encrypt(input, "varchar", "SaltBeta876543210");

        assertNotNull(result1);
        assertNotNull(result2);
        assertNotEquals(result1, result2, "Different salt keys must produce different ciphertexts");
    }
}
