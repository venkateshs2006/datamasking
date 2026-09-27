package com.enterprise.seedm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkDumpTableConfig {
    private String tableName;
    private long currentCount;
    private long targetCount;
    private int columnCount;
    private List<String> columns;
    private List<String> primaryKeys;
    private List<java.util.Map<String, String>> foreignKeys;
    private List<String> uniqueKeys;
}
