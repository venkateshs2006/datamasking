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

    @Test
    void testHoldOnlyPositiveNumbers() {
        // Test integers (positive, zero, negative input all map to positive output)
        for (int input : List.of(1, 2, 45678, 100000, 0, -50, -99999)) {
            Object enc = fpeService.encrypt(input, "integer");
            assertNotNull(enc);
            assertTrue(enc instanceof Integer);
            assertTrue((Integer) enc > 0, "Masked integer must be strictly positive: " + enc);
        }

        // Test longs
        for (long input : List.of(1L, 9876543210L, 100L, 0L, -12345L)) {
            Object enc = fpeService.encrypt(input, "bigint");
            assertNotNull(enc);
            assertTrue(enc instanceof Long);
            assertTrue((Long) enc > 0L, "Masked long must be strictly positive: " + enc);
        }

        // Test smallint / short
        for (short input : List.of((short) 1, (short) 300, (short) 0, (short) -15)) {
            Object enc = fpeService.encrypt(input, "smallint");
            assertNotNull(enc);
            int val = ((Number) enc).intValue();
            assertTrue(val > 0, "Masked short must be strictly positive: " + val);
        }
    }

    @Test
    void testUniqueValuesWithoutCollision() {
        java.util.Set<Integer> uniqueInts = new java.util.HashSet<>();
        int count = 1000;
        for (int i = 1; i <= count; i++) {
            Object enc = fpeService.encrypt(i, "integer");
            assertNotNull(enc);
            assertTrue(enc instanceof Integer);
            int val = (Integer) enc;
            assertTrue(val > 0, "Must be positive");
            assertTrue(uniqueInts.add(val), "Collision detected for input " + i + ": " + val);
        }
        assertEquals(count, uniqueInts.size(), "All encrypted integers must be 100% unique");

        java.util.Set<Long> uniqueLongs = new java.util.HashSet<>();
        for (long i = 1L; i <= count; i++) {
            Object enc = fpeService.encrypt(i, "bigint");
            assertNotNull(enc);
            assertTrue(enc instanceof Long);
            long val = (Long) enc;
            assertTrue(val > 0L, "Must be positive");
            assertTrue(uniqueLongs.add(val), "Collision detected for long input " + i + ": " + val);
        }
        assertEquals(count, uniqueLongs.size(), "All encrypted longs must be 100% unique");
    }

    @Test
    void testNumericStringPreservesDigitsAndPositive() {
        String numStr = "12345";
        Object enc = fpeService.encrypt(numStr, "varchar");
        assertNotNull(enc);
        String encStr = enc.toString();
        assertEquals(numStr.length(), encStr.length(), "Must preserve length");
        assertTrue(encStr.matches("^\\d+$"), "Must contain only positive numeric digits: " + encStr);
        assertNotEquals(numStr, encStr, "Must differ from original");
        assertTrue(Long.parseLong(encStr) > 0, "Must represent a positive number");
    }
}
