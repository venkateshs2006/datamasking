package coms.seedm.masking;

import com.seedm.model.ColumnMetadata;
import com.seedm.util.HmacSeedUtil;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Random;

/**
 * Shifts dates/timestamps by a deterministic random offset (-365..+365 days),
 * which hides the true date while preserving rough recency and ordering noise
 * useful for realistic-looking test data.
 */
@Component
public class DateMaskingStrategy implements MaskingStrategy {

    private static final int MAX_OFFSET_DAYS = 365;

    @Override
    public Object mask(Object value, String table, ColumnMetadata meta, String maskingKey) {
        if (value == null) {
            return null;
        }

        long seed = HmacSeedUtil.seedFor(maskingKey, table, meta.getName(), value.toString());
        Random random = new Random(seed);
        int offsetDays = random.nextInt(2 * MAX_OFFSET_DAYS + 1) - MAX_OFFSET_DAYS;

        if (value instanceof Timestamp ts) {
            return new Timestamp(ts.getTime() + offsetDays * 86_400_000L);
        }
        if (value instanceof Date d) {
            LocalDate shifted = d.toLocalDate().plusDays(offsetDays);
            return Date.valueOf(shifted);
        }
        if (value instanceof Time t) {
            // shifting a pure time value by days is meaningless; randomize minutes instead
            int offsetMinutes = random.nextInt(1440);
            return new Time((t.getTime() + offsetMinutes * 60_000L) % 86_400_000L);
        }
        if (value instanceof LocalDate ld) {
            return ld.plusDays(offsetDays);
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.plusDays(offsetDays);
        }

        return value;
    }
}
