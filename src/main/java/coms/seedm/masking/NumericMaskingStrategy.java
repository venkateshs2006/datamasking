package coms.seedm.masking;

import com.seedm.model.ColumnMetadata;
import com.seedm.util.HmacSeedUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/**
 * Deterministically replaces a numeric value with another value of the same
 * Java type, randomized within roughly the same magnitude as the original so
 * downstream aggregate math (sums, averages used for demos/tests) still looks
 * plausible without leaking the real figure.
 */
@Component
public class NumericMaskingStrategy implements MaskingStrategy {

    @Override
    public Object mask(Object value, String table, ColumnMetadata meta, String maskingKey) {
        if (value == null) {
            return null;
        }

        long seed = HmacSeedUtil.seedFor(maskingKey, table, meta.getName(), value.toString());
        Random random = new Random(seed);

        if (value instanceof BigDecimal bd) {
            double magnitude = Math.max(1.0, bd.abs().doubleValue());
            double factor = 0.4 + random.nextDouble() * 1.2; // 0.4x - 1.6x
            BigDecimal masked = BigDecimal.valueOf(magnitude * factor)
                    .setScale(bd.scale() > 0 ? bd.scale() : 2, RoundingMode.HALF_UP);
            return bd.signum() < 0 ? masked.negate() : masked;
        }
        if (value instanceof Integer) {
            int magnitude = Math.max(1, Math.abs((Integer) value));
            int masked = 1 + random.nextInt(magnitude * 2 + 1);
            return (Integer) value < 0 ? -masked : masked;
        }
        if (value instanceof Long) {
            long magnitude = Math.max(1L, Math.abs((Long) value));
            long masked = 1 + (long) (random.nextDouble() * magnitude * 2);
            return (Long) value < 0 ? -masked : masked;
        }
        if (value instanceof Double || value instanceof Float) {
            double magnitude = Math.max(1.0, Math.abs(((Number) value).doubleValue()));
            double masked = 0.4 * magnitude + random.nextDouble() * magnitude * 1.2;
            return ((Number) value).doubleValue() < 0 ? -masked : masked;
        }

        // fallback: unknown numeric subtype, mask as string of similar digit length
        String original = value.toString();
        StringBuilder sb = new StringBuilder();
        for (char c : original.toCharArray()) {
            sb.append(Character.isDigit(c) ? Character.forDigit(random.nextInt(10), 10) : c);
        }
        return sb.toString();
    }
}
