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
    private Map<String, PiiEntityInfo> detectedEntities = new HashMap<>(); // key: "table.column"
    
    private int totalPiiColumnsFound;
    private String engineUsed;
    private String statusMessage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PiiEntityInfo {
        private String table;
        private String column;
        private String category; // e.g. "EMAIL", "FULL_NAME", "PHONE", "ADDRESS", "FINANCE", "SSN", "DOB"
        private String ruleType; // "SFD", "PMD", "FPH"
        private String fakerMethod; // e.g. "faker.internet().emailAddress()"
        private double confidence; // e.g. 0.95
        private String reason;
    }
}
