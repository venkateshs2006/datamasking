package com.enterprise.seedm.service;

import com.enterprise.seedm.util.DataTypeConstrants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Format Preserving Encryption Service
 * Encrypts data while preserving its format (length and character set)
 * Ensures referential integrity across tables
 * Optimized with ThreadLocal digest caching and high-throughput bit operations.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FormatPreservingEncryptionService {

    private final MaskingConfigService maskingConfigService;

    private static final ThreadLocal<MessageDigest> SHA256_HOLDER = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    });

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private String getSalt() {
        return maskingConfigService.getConfig().getMaskingKey();
    }

    /**
     * Encrypts a value based on its data type while preserving format and referential integrity.
     */
    public Object encrypt(Object value, String dataType) {
        return encrypt(value, dataType, null);
    }

    /**
     * Encrypts a value with an explicit custom salt key.
     */
    public Object encrypt(Object value, String dataType, String customSalt) {
        if (value == null) {
            return null;
        }

        String salt = (customSalt != null && !customSalt.trim().isEmpty()) ? customSalt.trim() : getSalt();
        String type = dataType.toLowerCase();
        try {
            switch (type) {
                case "integer":
                case "int":
                    return encryptInt((Integer) value, DataTypeConstrants.INT_MIN_VALUE, DataTypeConstrants.INT_MAX_VALUE, salt);
                case "long":
                    return encryptLong((Long) value, DataTypeConstrants.LONG_MIN_VALUE, DataTypeConstrants.LONG_MAX_VALUE, salt);
                case "short":
                case "smallint":
                case "int2":
                    int shortVal = (value instanceof Short) ? (Short) value : (Integer) value;
                    return encryptInt(shortVal, DataTypeConstrants.SHORT_MIN_VALUE, DataTypeConstrants.SHORT_MAX_VALUE, salt);
                case "byte":
                    int byteVal = (value instanceof Byte) ? (Byte) value : (Integer) value;
                    return encryptInt(byteVal, DataTypeConstrants.BYTE_MIN_VALUE, DataTypeConstrants.BYTE_MAX_VALUE, salt);
                case "bigint":
                case "int8":
                    return encryptLong((Long) value, DataTypeConstrants.INT8_MIN_VALUE, DataTypeConstrants.INT8_MAX_VALUE, salt);
                case "int4":
                    return encryptInt((Integer) value, DataTypeConstrants.INT4_MIN_VALUE, DataTypeConstrants.INT4_MAX_VALUE, salt);
                case "float":
                case "real":
                case "float4":
                    return encryptFloat((Float) value, salt);
                case "double":
                case "float8":
                case "double precision":
                    return encryptDouble((Double) value, salt);
                case "uuid":
                    return encryptUUID(value.toString(), salt);
                case "string":
                case "varchar":
                case "text":
                case "character varying":
                case "char":
                case "character":
                    return encryptString(value.toString(), salt);
                case "boolean":
                case "bool":
                    return encryptBoolean((Boolean) value, salt);
                default:
                    log.warn("Unsupported data type for FPE: {}. Returning original value.", dataType);
                    return value;
            }
        } catch (Exception e) {
            log.error("Encryption failed for value: {} type: {}", value, dataType, e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    // --- Deterministic Encryption Logic ---

    private int encryptInt(int value, int min, int max, String salt) {
        long range = (long) max - min + 1;
        long hash = getHash(value, salt);
        long offset = Math.abs(hash % range);
        return (int) (min + offset);
    }

    private long encryptLong(long value, long min, long max, String salt) {
        java.math.BigInteger range = java.math.BigInteger.valueOf(max).subtract(java.math.BigInteger.valueOf(min)).add(java.math.BigInteger.ONE);
        java.math.BigInteger hashVal = new java.math.BigInteger(1, getHashBytes(String.valueOf(value), salt));
        java.math.BigInteger offset = hashVal.mod(range);
        return java.math.BigInteger.valueOf(min).add(offset).longValue();
    }

    private float encryptFloat(float value, String salt) {
        int bits = Float.floatToIntBits(value);
        int encryptedBits = encryptInt(bits, Integer.MIN_VALUE, Integer.MAX_VALUE, salt);
        return Float.intBitsToFloat(encryptedBits);
    }

    private double encryptDouble(double value, String salt) {
        long bits = Double.doubleToLongBits(value);
        long encryptedBits = encryptLong(bits, Long.MIN_VALUE, Long.MAX_VALUE, salt);
        return Double.longBitsToDouble(encryptedBits);
    }

    private String encryptUUID(String uuidStr, String salt) {
        byte[] hash = getHashBytes(uuidStr, salt);
        return UUID.nameUUIDFromBytes(hash).toString();
    }

    private String encryptString(String value, String salt) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        int targetLength = value.length();
        StringBuilder hexString = new StringBuilder(targetLength + 64);
        String currentInput = value;
        
        while (hexString.length() < targetLength) {
            byte[] hash = getHashBytes(currentInput, salt);
            for (byte b : hash) {
                hexString.append(HEX_CHARS[(b >> 4) & 0x0F]);
                hexString.append(HEX_CHARS[b & 0x0F]);
            }
            currentInput = hexString.toString();
        }
        
        return hexString.substring(0, targetLength);
    }
    
    private boolean encryptBoolean(boolean value, String salt) {
        long hash = getHash(value ? 1 : 0, salt);
        return hash % 2 == 0;
    }

    // --- High-Performance Helper Methods ---

    private long getHash(long value, String salt) {
        byte[] hashBytes = getHashBytes(String.valueOf(value), salt);
        long result = 0;
        for (int i = 0; i < 8 && i < hashBytes.length; i++) {
            result <<= 8;
            result |= (hashBytes[i] & 0xFF);
        }
        return result;
    }

    private byte[] getHashBytes(String input, String salt) {
        MessageDigest digest = SHA256_HOLDER.get();
        digest.reset();
        digest.update(input.getBytes(StandardCharsets.UTF_8));
        if (salt != null && !salt.isEmpty()) {
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
        }
        return digest.digest();
    }
}
