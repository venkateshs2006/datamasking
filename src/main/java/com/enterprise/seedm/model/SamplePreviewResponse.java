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
public class SamplePreviewResponse {
    private String tableName;
    @Builder.Default
    private List<String> columns = new ArrayList<>();
    @Builder.Default
    private Map<String, String> columnRules = new HashMap<>(); // colName -> "SFD", "PMD", "FPH", "NONE"
    @Builder.Default
    private List<Map<String, Object>> beforeRows = new ArrayList<>(); // Sanitized: PII redacted
    @Builder.Default
    private List<Map<String, Object>> afterRows = new ArrayList<>(); // Anonymized synthetic data
    private int rowCount;
    private int maskedColumnCount;
    private String message;
}
