package coms.seedm.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Masking rules. Column references are "table.column" (case-insensitive),
 * matching the sample payload exactly, e.g. "creditcards.cvv_hash".
 */
public class RulesConfig {

    @NotEmpty
    private List<String> maskingColumns;

    private List<String> constraintColumns;

    private List<String> partialMaskingColumns;

    @NotEmpty
    private List<String> targetTables;

    @NotBlank
    private String maskingKey;

    public List<String> getMaskingColumns() {
        return maskingColumns;
    }

    public void setMaskingColumns(List<String> maskingColumns) {
        this.maskingColumns = maskingColumns;
    }

    public List<String> getConstraintColumns() {
        return constraintColumns;
    }

    public void setConstraintColumns(List<String> constraintColumns) {
        this.constraintColumns = constraintColumns;
    }

    public List<String> getPartialMaskingColumns() {
        return partialMaskingColumns;
    }

    public void setPartialMaskingColumns(List<String> partialMaskingColumns) {
        this.partialMaskingColumns = partialMaskingColumns;
    }

    public List<String> getTargetTables() {
        return targetTables;
    }

    public void setTargetTables(List<String> targetTables) {
        this.targetTables = targetTables;
    }

    public String getMaskingKey() {
        return maskingKey;
    }

    public void setMaskingKey(String maskingKey) {
        this.maskingKey = maskingKey;
    }

    /** Lower-cased "table.column" set, built once for fast lookups. */
    public Set<String> maskingColumnsLower() {
        return toLowerSet(maskingColumns);
    }

    public Set<String> constraintColumnsLower() {
        return toLowerSet(constraintColumns);
    }

    public Set<String> partialMaskingColumnsLower() {
        return toLowerSet(partialMaskingColumns);
    }

    private static Set<String> toLowerSet(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.toLowerCase().trim())
                .collect(Collectors.toSet());
    }
}
