package coms.seedm.masking;

import com.seedm.model.ColumnMetadata;
import org.springframework.stereotype.Service;

@Service
public class MaskingService {

    private final StringMaskingStrategy stringStrategy;
    private final NumericMaskingStrategy numericStrategy;
    private final DateMaskingStrategy dateStrategy;
    private final BooleanMaskingStrategy booleanStrategy;
    private final PartialMaskingStrategy partialStrategy;

    public MaskingService(StringMaskingStrategy stringStrategy,
                           NumericMaskingStrategy numericStrategy,
                           DateMaskingStrategy dateStrategy,
                           BooleanMaskingStrategy booleanStrategy,
                           PartialMaskingStrategy partialStrategy) {
        this.stringStrategy = stringStrategy;
        this.numericStrategy = numericStrategy;
        this.dateStrategy = dateStrategy;
        this.booleanStrategy = booleanStrategy;
        this.partialStrategy = partialStrategy;
    }

    /** Full masking: picks a strategy purely from the column's JDBC type. */
    public Object fullyMask(Object value, String table, ColumnMetadata meta, String maskingKey) {
        if (meta.isBoolean()) {
            return booleanStrategy.mask(value, table, meta, maskingKey);
        }
        if (meta.isDateOrTime()) {
            return dateStrategy.mask(value, table, meta, maskingKey);
        }
        if (meta.isNumeric()) {
            return numericStrategy.mask(value, table, meta, maskingKey);
        }
        return stringStrategy.mask(value, table, meta, maskingKey);
    }

    /** Partial masking: always textual, preserves some characters for readability. */
    public Object partiallyMask(Object value, String table, ColumnMetadata meta, String maskingKey) {
        return partialStrategy.mask(value, table, meta, maskingKey);
    }
}
