package coms.seedm.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HexFormat;

/**
 * Turns (maskingKey, table, column, originalValue) into a deterministic value.
 * Deterministic masking means the same input always produces the same masked
 * output within a run/key, which keeps values consistent across tables (e.g.
 * the same customer appearing twice masks the same way) without ever needing
 * to store a lookup table.
 */
public final class HmacSeedUtil {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSeedUtil() {
    }

    public static byte[] hmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Unable to compute HMAC for masking", e);
        }
    }

    public static String hmacHex(String key, String data) {
        return HexFormat.of().formatHex(hmac(key, data));
    }

    /** Deterministic long seed, suitable for java.util.Random. */
    public static long seedFor(String maskingKey, String table, String column, String originalValue) {
        byte[] digest = hmac(maskingKey, table + "." + column + "=" + originalValue);
        return ByteBuffer.wrap(digest, 0, 8).getLong();
    }
}
