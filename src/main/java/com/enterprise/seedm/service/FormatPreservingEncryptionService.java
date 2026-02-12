package com.enterprise.seedm.service;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.fpe.FPEFF1Engine;
import org.bouncycastle.crypto.fpe.FPEEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.FPEParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Format Preserving Encryption Service
 * Encrypts data while preserving its format (length and character set)
 * Ensures referential integrity across tables
 */
@Service
@Slf4j
public class FormatPreservingEncryptionService {

    private final byte[] key;
    private final byte[] tweak;
    
    // Character sets for different data types
    private static final char[] DIGITS = "0123456789".toCharArray();
    private static final char[] ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public FormatPreservingEncryptionService(@Value("${seedm.migration.masking-key:DefaultSecretKey123}") String secretKey) {
        // Derive a 256-bit key from the provided secret string using HKDF
        this.key = deriveKey(secretKey);
        // Tweak is optional but recommended for FPE. Using a static tweak for consistency across runs.
        this.tweak = "seedm-tweak".getBytes(StandardCharsets.UTF_8);
    }

    private byte[] deriveKey(String secret) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(secret.getBytes(StandardCharsets.UTF_8), null, null));
        byte[] derivedKey = new byte[32]; // 256 bits
        hkdf.generateBytes(derivedKey, 0, 32);
        return derivedKey;
    }

    /**
     * Encrypts an integer value while preserving it as an integer
     */
    public Object encrypt(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Integer) {
            return encryptInteger((Integer) value);
        } else if (value instanceof Long) {
            return encryptLong((Long) value);
        } else if (value instanceof String) {
            String strVal = (String) value;
            // Check if it's a UUID
            try {
                UUID.fromString(strVal);
                return encryptUUID(strVal);
            } catch (IllegalArgumentException e) {
                // Not a UUID, treat as string
                return encryptString(strVal);
            }
        } else if (value instanceof UUID) {
            return UUID.fromString(encryptUUID(value.toString()));
        }

        // Fallback for unsupported types: return as is or convert to string and encrypt?
        // For safety, let's return as is but log warning
        log.warn("Unsupported type for FPE: {}. Returning original value.", value.getClass().getName());
        return value;
    }

    private int encryptInteger(int value) {
        String input = String.valueOf(value);
        String encrypted = encryptStringWithAlphabet(input, DIGITS);
        // Handle potential leading zeros or overflow if necessary, but FPE preserves length so it should be fine
        // unless the original value didn't fill the full range.
        // However, for IDs, we usually want to keep them positive.
        return Integer.parseInt(encrypted);
    }

    private long encryptLong(long value) {
        String input = String.valueOf(value);
        String encrypted = encryptStringWithAlphabet(input, DIGITS);
        return Long.parseLong(encrypted);
    }

    private String encryptString(String value) {
        return encryptStringWithAlphabet(value, ALPHANUMERIC);
    }

    private String encryptUUID(String uuidStr) {
        // UUID format: 8-4-4-4-12 hex digits
        // We can encrypt just the hex digits and put the hyphens back
        String raw = uuidStr.replace("-", "");
        String encryptedRaw = encryptStringWithAlphabet(raw, HEX);
        
        StringBuilder sb = new StringBuilder(encryptedRaw);
        sb.insert(8, "-");
        sb.insert(13, "-");
        sb.insert(18, "-");
        sb.insert(23, "-");
        
        return sb.toString();
    }

    private String encryptStringWithAlphabet(String input, char[] alphabet) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // FPE requires at least 2 characters usually. If length is 1, we can't really "shuffle" it securely with FPE.
        // For length 1, we might just map it using a simple substitution cipher derived from the key.
        if (input.length() < 2) {
            return simpleSubstitution(input, alphabet);
        }

        try {
            // Manually map characters to indexes
            byte[] inputBytes = convertToIndexes(input, alphabet);
            
            FPEEngine engine = new FPEFF1Engine(new AESEngine());
            engine.init(true, new FPEParameters(new KeyParameter(key), alphabet.length, tweak));
            
            byte[] outputBytes = new byte[inputBytes.length];
            engine.processBlock(inputBytes, 0, inputBytes.length, outputBytes, 0);
            
            return convertToChars(outputBytes, alphabet);
        } catch (Exception e) {
            log.error("FPE Encryption failed", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private byte[] convertToIndexes(String input, char[] alphabet) {
        Map<Character, Integer> indexMap = new HashMap<>();
        for (int i = 0; i < alphabet.length; i++) {
            indexMap.put(alphabet[i], i);
        }

        byte[] indexes = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            Character c = input.charAt(i);
            if (!indexMap.containsKey(c)) {
                throw new IllegalArgumentException("Character '" + c + "' not found in alphabet");
            }
            indexes[i] = indexMap.get(c).byteValue();
        }
        return indexes;
    }

    private String convertToChars(byte[] indexes, char[] alphabet) {
        StringBuilder sb = new StringBuilder(indexes.length);
        for (byte index : indexes) {
            // Handle unsigned byte issue if necessary, though alphabet size is usually small
            int idx = index & 0xFF; 
            if (idx >= alphabet.length) {
                 throw new IllegalArgumentException("Index " + idx + " out of bounds for alphabet");
            }
            sb.append(alphabet[idx]);
        }
        return sb.toString();
    }

    private String simpleSubstitution(String input, char[] alphabet) {
        // Simple shift or mapping for single characters based on the key hash
        int shift = Arrays.hashCode(key) % alphabet.length;
        if (shift < 0) shift += alphabet.length;
        
        char c = input.charAt(0);
        int index = -1;
        for (int i = 0; i < alphabet.length; i++) {
            if (alphabet[i] == c) {
                index = i;
                break;
            }
        }
        
        if (index == -1) return input; // Character not in alphabet
        
        int newIndex = (index + shift) % alphabet.length;
        return String.valueOf(alphabet[newIndex]);
    }
}
