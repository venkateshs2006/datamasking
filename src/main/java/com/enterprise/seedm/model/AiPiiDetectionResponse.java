package com.enterprise.seedm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPiiDetectionResponse {
    @Builder.Default
    private List<String> maskingColumns = new ArrayList<>(); // SFD (table.column)
    
    @Builder.Default
    private List<String> partialMaskingColumns = new ArrayList<>(); // PMD (table.column)
    
    @Builder.Default
    private List<String> constraintColumns = new ArrayList<>(); // FPH (table.column)

    @Builder.Default
    private List<String> primaryKeyColumns = new ArrayList<>(); // Primary Keys (table.column)

    @Builder.Default
    private List<String> foreignKeyColumns = new ArrayList<>(); // Foreign Keys (table.column)

    @Builder.Default
    private List<String> uniqueKeyColumns = new ArrayList<>(); // Unique Keys (table.column)

    @Builder.Default
    private List<String> tableMappingColumns = new ArrayList<>(); // Junction/Mapping Columns (table.column)
    
    @Builder.Default
    private Map<String, PiiEntityInfo> detectedEntities = new HashMap<>(); // key: "table.column"
    
    private int totalPiiColumnsFound;
    private int totalKeyColumnsFound;
    private String engineUsed;
    private String statusMessage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PiiEntityInfo {
        private String table;
        private String column;
        private String category; // e.g. "EMAIL", "FULL_NAME", "CREDIT_CARD", "PRIMARY_KEY", "FOREIGN_KEY", etc.
        private String ruleType; // "SFD", "PMD", "FPH", "NONE"
        private String fakerMethod; // e.g. "faker.internet().emailAddress()", "DeterministicFPH()"
        private double confidence; // e.g. 0.95
        private String reason;
        private String keyType; // "PRIMARY_KEY", "FOREIGN_KEY", "UNIQUE_KEY", "TABLE_MAPPING", "NONE"
        private String targetTable; // referenced table if foreign key or mapping column
        private String targetColumn; // referenced column if foreign key or mapping column
    }
}
