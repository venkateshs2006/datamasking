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
        int count = 10000;
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

    @Test
    void test8000AlphaNumericStringUniqueness() {
        java.util.Set<String> unique = new java.util.HashSet<>();
        int count = 10000;
        for (int i = 0; i < count; i++) {
            String str = String.format("A%04d", i);
            Object enc = fpeService.encrypt(str, "string");
            assertNotNull(enc);
            assertTrue(unique.add(enc.toString()), "Collision for " + str + ": " + enc + " at count=" + i);
        }
        assertEquals(count, unique.size());
    }

    @Test
    void test8000FiveDigitNumericStringUniqueness() {
        java.util.Set<String> uniqueNumericStrings = new java.util.HashSet<>();
        int count = 99999;
        // Test zero-padded sequence 00001 to 99999
        for (int i = 1; i <= count; i++) {
            String str = String.format("%05d", i);
            Object enc = fpeService.encrypt(str, "varchar");
            assertNotNull(enc);
            assertEquals(5, enc.toString().length());
            assertTrue(enc.toString().matches("^\\d+$"));
            assertTrue(uniqueNumericStrings.add(enc.toString()), "Numeric collision detected for input " + str + ": " + enc + " at record " + i);
        }
        assertEquals(count, uniqueNumericStrings.size());
    }

    @Test
    void test20000AlphabeticStringUniqueness() {
        java.util.Set<String> unique = new java.util.HashSet<>();
        int count = 20000;
        for (int i = 0; i < count; i++) {
            int n = i;
            char[] chars = new char[5];
            for (int k = 4; k >= 0; k--) {
                chars[k] = (char) ('a' + (n % 26));
                n /= 26;
            }
            String str = new String(chars);
            Object enc = fpeService.encrypt(str, "varchar");
            assertNotNull(enc);
            assertEquals(5, enc.toString().length());
            assertTrue(unique.add(enc.toString()), "Alphabetic collision for " + str + ": " + enc + " at record " + i);
        }
        assertEquals(count, unique.size());
    }

    @Test
    void test8000FiveDigitIntegerUniqueness() {
        java.util.Set<Integer> uniqueInts = new java.util.HashSet<>();
        int count = 20000;
        for (int i = 10000; i < 10000 + count; i++) {
            Object enc = fpeService.encrypt(i, "integer");
            assertNotNull(enc);
            assertTrue(enc instanceof Integer);
            int val = (Integer) enc;
            assertTrue(val > 0, "Must be positive");
            assertTrue(uniqueInts.add(val), "Collision detected for input " + i + ": " + val + " at record " + i);
        }
        assertEquals(count, uniqueInts.size());
    }

    @Test
    void testVarchar2AndVarry2DataTypes() {
        // Test varchar(2) and varry(2) data type inputs
        Object enc1 = fpeService.encrypt("NY", "varchar(2)");
        assertNotNull(enc1);
        assertEquals(2, enc1.toString().length());

        Object enc2 = fpeService.encrypt("CA", "varry(2)");
        assertNotNull(enc2);
        assertEquals(2, enc2.toString().length());

        Object enc3 = fpeService.encrypt("US", "varying(2)");
        assertNotNull(enc3);
        assertEquals(2, enc3.toString().length());
    }

    @Test
    void testTwoCharStringUniqueness() {
        java.util.Set<String> unique = new java.util.HashSet<>();
        // All 676 2-letter combinations: aa, ab, ..., zz
        for (int i = 0; i < 26 * 26; i++) {
            char c1 = (char) ('a' + (i / 26));
            char c2 = (char) ('a' + (i % 26));
            String str = "" + c1 + c2;
            Object enc = fpeService.encrypt(str, "varchar(2)");
            assertNotNull(enc);
            assertEquals(2, enc.toString().length());
            assertTrue(unique.add(enc.toString()), "Collision for " + str + ": " + enc);
        }
        assertEquals(676, unique.size(), "All 676 2-char letter pairs must be 100% unique without collisions");
    }

    @Test
    void test20000AlphanumericFiveCharUniqueness() {
        java.util.Set<String> unique = new java.util.HashSet<>();
        int count = 20000;
        for (int i = 0; i < count; i++) {
            // Diverse 5-char alphanumeric strings
            long s = i;
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 5; k++) {
                long d = s % 36;
                s /= 36;
                char c = d < 10 ? (char) ('0' + d) : (char) ('A' + (d - 10));
                sb.append(c);
            }
            String str = sb.reverse().toString();
            Object enc = fpeService.encrypt(str, "varchar(5)");
            assertNotNull(enc);
            assertEquals(5, enc.toString().length());
            assertTrue(unique.add(enc.toString()), "Alphanumeric collision for " + str + ": " + enc + " at count=" + i);
        }
        assertEquals(count, unique.size(), "20,000 5-char alphanumeric strings must have 0 collisions");
    }
}
