package coms.seedm.masking;

import com.seedm.model.ColumnMetadata;

public interface MaskingStrategy {

    /**
     * @param value       original column value (may be null)
     * @param table       table name, used to salt the deterministic seed
     * @param meta        column metadata (type info)
     * @param maskingKey  secret key from rules.maskingKey
     * @return masked value, same broad Java type as the input where practical
     */
    Object mask(Object value, String table, ColumnMetadata meta, String maskingKey);
}
