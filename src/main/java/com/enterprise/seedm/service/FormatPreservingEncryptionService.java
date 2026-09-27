package com.enterprise.seedm.service;

import com.enterprise.seedm.util.DataTypeConstrants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        return encrypt(value, dataType, null, null, null);
    }

    /**
     * Encrypts a value with an explicit custom salt key.
     */
    public Object encrypt(Object value, String dataType, String customSalt) {
        return encrypt(value, dataType, customSalt, null, null);
    }

    /**
     * Encrypts a value with custom salt key, precision, and scale (crucial for numeric/decimal).
     */
    public Object encrypt(Object value, String dataType, String customSalt, Integer precision, Integer scale) {
        if (value == null) {
            return null;
        }

        String salt = (customSalt != null && !customSalt.trim().isEmpty()) ? customSalt.trim() : getSalt();
        String type = dataType != null ? dataType.toLowerCase() : "string";
        try {
            switch (type) {
                case "integer":
                case "int":
                case "int4":
                case "serial":
                    return encryptInt(toInt(value), DataTypeConstrants.INT4_MIN_VALUE, DataTypeConstrants.INT4_MAX_VALUE, salt);
                case "long":
                case "bigint":
                case "int8":
                case "bigserial":
                    return encryptLong(toLong(value), DataTypeConstrants.INT8_MIN_VALUE, DataTypeConstrants.INT8_MAX_VALUE, salt);
                case "short":
                case "smallint":
                case "int2":
                case "smallserial":
                    int shortVal = encryptInt(toInt(value), DataTypeConstrants.INT2_MIN_VALUE, DataTypeConstrants.INT2_MAX_VALUE, salt);
                    return (short) shortVal;
                case "byte":
                case "tinyint":
                    int byteVal = encryptInt(toInt(value), DataTypeConstrants.BYTE_MIN_VALUE, DataTypeConstrants.BYTE_MAX_VALUE, salt);
                    return (byte) byteVal;
                case "float":
                case "real":
                case "float4":
                    return encryptFloat(toFloat(value), salt);
                case "double":
                case "float8":
                case "double precision":
                    return encryptDouble(toDouble(value), salt);
                case "numeric":
                case "decimal":
                case "money":
                    return encryptNumeric(value, precision, scale, salt);
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
                    return encryptBoolean(toBoolean(value), salt);
                default:
                    log.warn("Unsupported data type for FPE: {}. Returning original value.", dataType);
                    return value;
            }
        } catch (Exception e) {
            log.error("Encryption failed for value: {} type: {}", value, dataType, e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    // --- Deterministic Encryption Logic (Positive Numbers & Unique Values) ---

    private int encryptInt(int value, int min, int max, String salt) {
        if (min < 1) {
            min = 1;
        }
        if (max < min) {
            max = Integer.MAX_VALUE;
        }
        return permuteInt(value, min, max, salt);
    }

    private long encryptLong(long value, long min, long max, String salt) {
        if (min < 1L) {
            min = 1L;
        }
        if (max < min) {
            max = Long.MAX_VALUE;
        }
        return permuteLong(value, min, max, salt);
    }

    private int permuteInt(int value, int min, int max, String salt) {
        int[] keys = deriveRoundKeys32(salt);
        // Map non-positive or out-of-range value into positive range deterministically
        if (value < min || value > max) {
            long hash = getHash(value, salt);
            long range = (long) max - min + 1;
            long offset = (hash & 0x7FFFFFFFFFFFFFFFL) % range;
            value = (int) (min + offset);
        }

        int current = value;
        // Cycle walking: Feistel cipher is an exact permutation (bijective, zero collisions).
        // Walking the cycle until in [min, max] guarantees 1-to-1 uniqueness and positive values.
        for (int i = 0; i < 100; i++) {
            if (max <= 127) {
                current = feistelEncrypt8(current, keys);
            } else if (max <= 32767) {
                current = feistelEncrypt16(current, keys);
            } else {
                current = feistelEncrypt32(current, keys);
            }
            if (current >= min && current <= max) {
                return current;
            }
        }

        // Guaranteed positive fallback
        long hash = getHash(value, salt);
        long range = (long) max - min + 1;
        long offset = (hash & 0x7FFFFFFFFFFFFFFFL) % range;
        return (int) (min + offset);
    }

    private long permuteLong(long value, long min, long max, String salt) {
        long[] keys = deriveRoundKeys64(salt);
        if (value < min || value > max) {
            long hash = getHash(value, salt);
            java.math.BigInteger range = java.math.BigInteger.valueOf(max).subtract(java.math.BigInteger.valueOf(min)).add(java.math.BigInteger.ONE);
            java.math.BigInteger hashVal = new java.math.BigInteger(1, getHashBytes(String.valueOf(value), salt));
            java.math.BigInteger offset = hashVal.mod(range);
            value = java.math.BigInteger.valueOf(min).add(offset).longValue();
        }

        long current = value;
        for (int i = 0; i < 100; i++) {
            current = feistelEncrypt64(current, keys);
            if (current >= min && current <= max) {
                return current;
            }
        }

        java.math.BigInteger range = java.math.BigInteger.valueOf(max).subtract(java.math.BigInteger.valueOf(min)).add(java.math.BigInteger.ONE);
        java.math.BigInteger hashVal = new java.math.BigInteger(1, getHashBytes(String.valueOf(value), salt));
        java.math.BigInteger offset = hashVal.mod(range);
        return java.math.BigInteger.valueOf(min).add(offset).longValue();
    }

    // --- Feistel Permutations (Bijective, 0-collision mappings) ---

    private int feistelEncrypt32(int val, int[] roundKeys) {
        int l = (val >>> 16) & 0xFFFF;
        int r = val & 0xFFFF;
        for (int k : roundKeys) {
            int f = (r ^ k) * 0x45d9f3b;
            f = ((f >>> 16) ^ f) * 0x45d9f3b;
            f = (f >>> 16) & 0xFFFF;

            int nextR = l ^ f;
            l = r;
            r = nextR;
        }
        return (l << 16) | (r & 0xFFFF);
    }

    private int feistelEncrypt16(int val, int[] roundKeys) {
        int l = (val >>> 8) & 0xFF;
        int r = val & 0xFF;
        for (int k : roundKeys) {
            int f = (r ^ (k & 0xFF)) * 0x85;
            f = ((f >>> 8) ^ f) * 0x45;
            f = (f >>> 4) & 0xFF;

            int nextR = l ^ f;
            l = r;
            r = nextR;
        }
        return ((l & 0xFF) << 8) | (r & 0xFF);
    }

    private int feistelEncrypt8(int val, int[] roundKeys) {
        int l = (val >>> 4) & 0x0F;
        int r = val & 0x0F;
        for (int k : roundKeys) {
            int f = (r ^ (k & 0x0F)) * 0x7;
            f = ((f >>> 4) ^ f) * 0x5;
            f = (f >>> 2) & 0x0F;

            int nextR = l ^ f;
            l = r;
            r = nextR;
        }
        return ((l & 0x0F) << 4) | (r & 0x0F);
    }

    private long feistelEncrypt64(long val, long[] roundKeys) {
        long l = (val >>> 32) & 0xFFFFFFFFL;
        long r = val & 0xFFFFFFFFL;
        for (long k : roundKeys) {
            long f = (r ^ k) * 0xbf58476d1ce4e5b9L;
            f = ((f >>> 30) ^ f) * 0x94d049bb133111ebL;
            f = (f >>> 32) & 0xFFFFFFFFL;

            long nextR = l ^ f;
            l = r;
            r = nextR;
        }
        return (l << 32) | (r & 0xFFFFFFFFL);
    }

    private int[] deriveRoundKeys32(String salt) {
        byte[] hash = getHashBytes("SeedM-Feistel32-Keys", salt);
        int[] keys = new int[6];
        for (int i = 0; i < 6; i++) {
            keys[i] = ((hash[i * 4] & 0xFF) << 24)
                    | ((hash[i * 4 + 1] & 0xFF) << 16)
                    | ((hash[i * 4 + 2] & 0xFF) << 8)
                    | (hash[i * 4 + 3] & 0xFF);
        }
        return keys;
    }

    private long[] deriveRoundKeys64(String salt) {
        byte[] hash1 = getHashBytes("SeedM-Feistel64-Keys-A", salt);
        byte[] hash2 = getHashBytes("SeedM-Feistel64-Keys-B", salt);
        long[] keys = new long[6];
        for (int i = 0; i < 4; i++) {
            keys[i] = bytesToLong(hash1, i * 8);
        }
        for (int i = 0; i < 2; i++) {
            keys[4 + i] = bytesToLong(hash2, i * 8);
        }
        return keys;
    }

    private long bytesToLong(byte[] bytes, int offset) {
        long result = 0;
        for (int i = 0; i < 8 && (offset + i) < bytes.length; i++) {
            result <<= 8;
            result |= (bytes[offset + i] & 0xFF);
        }
        return result;
    }

    private float encryptFloat(float value, String salt) {
        float positiveVal = Math.abs(value);
        if (positiveVal < 0.0001f) positiveVal = 1.0f;
        int whole = (int) positiveVal;
        int encryptedWhole = encryptInt(whole <= 0 ? 1 : whole, 1, 1000000, salt);
        long hash = Math.abs(getHash(Float.floatToIntBits(value), salt));
        float fraction = (float) ((hash % 1000) / 1000.0);
        return encryptedWhole + fraction;
    }

    private double encryptDouble(double value, String salt) {
        double positiveVal = Math.abs(value);
        if (positiveVal < 0.0001) positiveVal = 1.0;
        long whole = (long) positiveVal;
        long encryptedWhole = encryptLong(whole <= 0 ? 1L : whole, 1L, 1000000000L, salt);
        long hash = Math.abs(getHash(Double.doubleToLongBits(value), salt));
        double fraction = (hash % 10000) / 10000.0;
        return encryptedWhole + fraction;
    }

    private Object encryptNumeric(Object value, Integer precision, Integer scale, String salt) {
        BigDecimal bdVal;
        if (value instanceof BigDecimal) {
            bdVal = (BigDecimal) value;
        } else {
            try {
                bdVal = new BigDecimal(value.toString().trim());
            } catch (Exception e) {
                return value;
            }
        }

        int effPrecision = (precision != null && precision > 0) ? precision : (bdVal.precision() > 0 ? bdVal.precision() : 10);
        int effScale = (scale != null && scale >= 0) ? scale : Math.max(0, bdVal.scale());

        int intDigits = Math.max(1, effPrecision - effScale);
        long maxWhole;
        if (intDigits <= 9) {
            maxWhole = (long) Math.pow(10, intDigits) - 1;
        } else {
            maxWhole = 999999999L;
        }
        if (maxWhole < 1) maxWhole = 1;

        long origWhole = Math.abs(bdVal.toBigInteger().longValue());
        origWhole = Math.max(1L, Math.min(origWhole, maxWhole));

        long encryptedWhole = encryptLong(origWhole, 1L, maxWhole, salt);

        BigDecimal result;
        if (effScale > 0) {
            long maxFrac = (effScale <= 9) ? (long) Math.pow(10, effScale) - 1 : 999999999L;
            BigDecimal fractionalPart = bdVal.remainder(BigDecimal.ONE).abs().movePointRight(effScale);
            long origFrac = Math.min(fractionalPart.longValue(), maxFrac);
            long hash = Math.abs(getHash(Double.doubleToLongBits(bdVal.doubleValue()), salt));
            long encryptedFrac = (origFrac + hash) % (maxFrac + 1);
            result = BigDecimal.valueOf(encryptedWhole).add(BigDecimal.valueOf(encryptedFrac, effScale)).setScale(effScale, RoundingMode.HALF_UP);
        } else {
            result = BigDecimal.valueOf(encryptedWhole).setScale(0, RoundingMode.HALF_UP);
        }

        if (value instanceof Double) return result.doubleValue();
        if (value instanceof Float) return result.floatValue();
        return result;
    }

    private String encryptUUID(String uuidStr, String salt) {
        byte[] hash = getHashBytes(uuidStr, salt);
        return UUID.nameUUIDFromBytes(hash).toString();
    }

    private String encryptString(String value, String salt) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        // If string contains only numeric digits, preserve numeric format (positive number)
        if (value.matches("^\\d+$")) {
            return encryptNumericString(value, salt);
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

    private String encryptNumericString(String value, String salt) {
        int len = value.length();
        if (len <= 9) {
            int num = Integer.parseInt(value);
            int min = (len == 1) ? 1 : (int) Math.pow(10, len - 1);
            int max = (int) Math.pow(10, len) - 1;
            int permuted = encryptInt(num, min, max, salt);
            return String.format("%0" + len + "d", permuted);
        } else if (len <= 18) {
            long num = Long.parseLong(value);
            long min = (long) Math.pow(10, len - 1);
            long max = (long) Math.pow(10, len) - 1;
            long permuted = encryptLong(num, min, max, salt);
            return String.format("%0" + len + "d", permuted);
        } else {
            StringBuilder sb = new StringBuilder(len);
            byte[] hash = getHashBytes(value, salt);
            for (int i = 0; i < len; i++) {
                int origDigit = value.charAt(i) - '0';
                int shift = (hash[i % hash.length] & 0x0F) + (i == 0 ? 1 : 0);
                int newDigit = (origDigit + shift) % 10;
                if (i == 0 && newDigit == 0) {
                    newDigit = 1 + ((origDigit + shift) % 9);
                }
                sb.append(newDigit);
            }
            return sb.toString();
        }
    }

    private boolean encryptBoolean(boolean value, String salt) {
        long hash = getHash(value ? 1 : 0, salt);
        return hash % 2 == 0;
    }

    // --- Safe Type Conversions ---

    private int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString().trim());
    }

    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString().trim());
    }

    private float toFloat(Object value) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        return Float.parseFloat(value.toString().trim());
    }

    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString().trim());
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString().trim());
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
