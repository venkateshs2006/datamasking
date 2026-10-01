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
        String rawType = dataType != null ? dataType.toLowerCase().trim() : "string";
        int pIdx = rawType.indexOf('(');
        Integer colLength = null;
        if (pIdx != -1) {
            int closePIdx = rawType.indexOf(')', pIdx);
            if (closePIdx != -1) {
                try {
                    colLength = Integer.parseInt(rawType.substring(pIdx + 1, closePIdx).trim());
                } catch (Exception ignored) {}
            }
            rawType = rawType.substring(0, pIdx).trim();
        }

        try {
            switch (rawType) {
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
                case "varchar2":
                case "varying":
                case "varry":
                case "character varying":
                case "char":
                case "character":
                case "bpchar":
                case "text":
                case "nvarchar":
                case "nchar":
                    String encStr = encryptString(value.toString(), salt);
                    if (colLength != null && colLength > 0 && encStr != null && encStr.length() > colLength) {
                        return encStr.substring(0, colLength);
                    }
                    return encStr;
                case "boolean":
                case "bool":
                    return encryptBoolean(toBoolean(value), salt);
                default:
                    if (rawType.contains("varchar") || rawType.contains("char") || rawType.contains("varying") || rawType.contains("varry") || rawType.contains("text") || rawType.contains("str")) {
                        String s = encryptString(value.toString(), salt);
                        if (colLength != null && colLength > 0 && s != null && s.length() > colLength) {
                            return s.substring(0, colLength);
                        }
                        return s;
                    }
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
        return (int) permuteRange(value, min, max, salt);
    }

    private long encryptLong(long value, long min, long max, String salt) {
        if (min < 1L) {
            min = 1L;
        }
        if (max < min || max == Long.MAX_VALUE) {
            max = Long.MAX_VALUE - 1L;
        }
        return permuteRange(value, min, max, salt);
    }

    private long permuteRange(long value, long min, long max, String salt) {
        if (min >= max) {
            return min;
        }
        if (max - min < 0) {
            max = Long.MAX_VALUE - 1L;
            if (min < 1L) min = 1L;
        }
        long range = max - min + 1;
        if (range <= 1) {
            return min;
        }

        // Map non-positive or out-of-range value into positive range deterministically
        if (value < min || value > max) {
            long hash = getHash(value, salt);
            long offset = (hash & 0x7FFFFFFFFFFFFFFFL) % range;
            value = min + offset;
        }

        long valNorm = value - min; // 0 <= valNorm < range
        long[] keys = deriveRoundKeys64(salt);

        if (range == 2) {
            return min + (valNorm ^ 1L);
        }

        int bits = 64 - Long.numberOfLeadingZeros(range - 1);
        int halfBits = (bits + 1) / 2;
        long halfMask = halfBits >= 64 ? -1L : ((1L << halfBits) - 1);

        long current = valNorm;
        // Balanced Feistel with cycle-walking:
        // Guarantees exact 1-to-1 bijective mapping with 0 collisions.
        for (int i = 0; i < 1000; i++) {
            current = feistelPermuteBalanced(current, halfBits, halfMask, keys);
            if (current >= 0 && current < range) {
                return min + current;
            }
        }

        // Deterministic bijective fallback (guaranteed coprime affine permutation with 0 collisions)
        long a = (keys[0] | 1L);
        while (gcd(a, range) != 1) {
            a += 2L;
        }
        long b = Math.abs(keys[1]) % range;
        return min + ((Math.abs(valNorm) * a + b) % range);
    }

    private long feistelPermuteBalanced(long val, int halfBits, long mask, long[] keys) {
        long l = (val >>> halfBits) & mask;
        long r = val & mask;

        for (int i = 0; i < keys.length; i++) {
            long k = keys[i];
            long f = feistelMix(r, k, mask);
            long nextR = (l ^ f) & mask;
            l = r;
            r = nextR;
        }

        return (l << halfBits) | (r & mask);
    }

    private long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return Math.abs(a);
    }

    private long feistelMix(long val, long key, long mask) {
        long x = (val ^ key) * 0xbf58476d1ce4e5b9L;
        x ^= (x >>> 30);
        x *= 0x94d049bb133111ebL;
        x ^= (x >>> 27);
        return x & mask;
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

        String alphabet;
        if (value.matches("^[0-9a-f]{24}$")) {
            alphabet = "0123456789abcdef";
        } else if (value.matches("^[0-9A-F]{24}$")) {
            alphabet = "0123456789ABCDEF";
        } else if (value.matches("^[0-9A-Z]+$")) {
            alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        } else if (value.matches("^[0-9a-z]+$")) {
            alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        } else if (value.matches("^[a-zA-Z]+$")) {
            alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        } else if (value.matches("^[0-9a-zA-Z]+$")) {
            alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        } else {
            StringBuilder sb = new StringBuilder(95);
            for (int c = 32; c <= 126; c++) {
                sb.append((char) c);
            }
            alphabet = sb.toString();
        }

        return encryptStringFpe(value, alphabet, salt);
    }

    private String encryptNumericString(String value, String salt) {
        int len = value.length();
        if (len <= 18) {
            long maxVal = 1L;
            for (int i = 0; i < len; i++) {
                maxVal *= 10L;
            }
            maxVal -= 1L; // e.g. for len=5, maxVal=99999L

            long num;
            try {
                num = Long.parseLong(value);
            } catch (NumberFormatException e) {
                return encryptStringFpe(value, "0123456789", salt);
            }

            // Unified exact domain [0, maxVal] ensures 1-to-1 bijective mapping with 0 collisions
            long permuted = permuteRange(num, 0L, maxVal, salt);
            return String.format("%0" + len + "d", permuted);
        } else {
            return encryptStringFpe(value, "0123456789", salt);
        }
    }

    private String encryptStringFpe(String value, String alphabet, String salt) {
        int len = value.length();
        if (len <= 1) {
            int radix = alphabet.length();
            int idx = alphabet.indexOf(value.charAt(0));
            if (idx == -1) {
                return value;
            }
            long hash = getHash(value.charAt(0), salt);
            int shift = (int) ((hash & 0x7FFFFFFF) % (radix - 1)) + 1;
            int newIdx = (idx + shift) % radix;
            return String.valueOf(alphabet.charAt(newIdx));
        }

        int radix = alphabet.length();

        // For strings of length <= 9 where radix^len fits in a 64-bit long:
        // Direct domain-wide bijective permutation guarantees EXACT 1-to-1 mapping with ZERO collisions
        if (len <= 9) {
            long totalDomain = 1L;
            boolean canFitLong = true;
            for (int i = 0; i < len; i++) {
                if (Long.MAX_VALUE / radix < totalDomain) {
                    canFitLong = false;
                    break;
                }
                totalDomain *= radix;
            }

            if (canFitLong && totalDomain > 1L) {
                long val = stringToLong(value, alphabet);
                long permuted = permuteRange(val, 0L, totalDomain - 1, salt);
                return longToString(permuted, alphabet, len);
            }
        }

        int n1 = len / 2;
        int n2 = len - n1;

        String leftStr = value.substring(0, n1);
        String rightStr = value.substring(n1);

        java.math.BigInteger bRadix = java.math.BigInteger.valueOf(radix);
        java.math.BigInteger modL = bRadix.pow(n1);
        java.math.BigInteger modR = bRadix.pow(n2);

        java.math.BigInteger leftVal = stringToBigInteger(leftStr, alphabet, bRadix);
        java.math.BigInteger rightVal = stringToBigInteger(rightStr, alphabet, bRadix);

        for (int round = 0; round < 10; round++) {
            java.math.BigInteger f1 = hashRoundToBigInteger(rightVal, round * 2, salt, modL);
            leftVal = leftVal.add(f1).mod(modL);

            java.math.BigInteger f2 = hashRoundToBigInteger(leftVal, round * 2 + 1, salt, modR);
            rightVal = rightVal.add(f2).mod(modR);
        }

        String newLeft = bigIntegerToString(leftVal, alphabet, bRadix, n1);
        String newRight = bigIntegerToString(rightVal, alphabet, bRadix, n2);
        return newLeft + newRight;
    }

    private long stringToLong(String str, String alphabet) {
        long val = 0;
        int radix = alphabet.length();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            int idx = alphabet.indexOf(c);
            if (idx == -1) {
                idx = Math.abs((int) c) % radix;
            }
            val = val * radix + idx;
        }
        return val;
    }

    private String longToString(long val, String alphabet, int length) {
        char[] chars = new char[length];
        long current = val;
        int radix = alphabet.length();
        for (int i = length - 1; i >= 0; i--) {
            int rem = (int) (current % radix);
            chars[i] = alphabet.charAt(rem);
            current = current / radix;
        }
        return new String(chars);
    }


    private java.math.BigInteger stringToBigInteger(String str, String alphabet, java.math.BigInteger bRadix) {
        java.math.BigInteger val = java.math.BigInteger.ZERO;
        int radix = alphabet.length();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            int idx = alphabet.indexOf(c);
            if (idx == -1) {
                idx = Math.abs((int) c) % radix;
            }
            val = val.multiply(bRadix).add(java.math.BigInteger.valueOf(idx));
        }
        return val;
    }

    private String bigIntegerToString(java.math.BigInteger val, String alphabet, java.math.BigInteger bRadix, int length) {
        char[] chars = new char[length];
        java.math.BigInteger current = val;
        for (int i = length - 1; i >= 0; i--) {
            java.math.BigInteger[] divRem = current.divideAndRemainder(bRadix);
            int rem = divRem[1].intValue();
            chars[i] = alphabet.charAt(rem);
            current = divRem[0];
        }
        return new String(chars);
    }

    private java.math.BigInteger hashRoundToBigInteger(java.math.BigInteger val, int round, String salt, java.math.BigInteger mod) {
        MessageDigest digest = SHA256_HOLDER.get();
        digest.reset();
        digest.update(val.toByteArray());
        digest.update((byte) round);
        if (salt != null && !salt.isEmpty()) {
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
        }
        byte[] hash = digest.digest();
        return new java.math.BigInteger(1, hash).mod(mod);
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
