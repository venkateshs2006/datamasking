package com.enterprise.seedm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkDumpProgress {
    private String executionId;
    private Long jobId;
    private String dbType;
    private String status; // PENDING, RUNNING, COMPLETED, FAILED
    private int overallPercent;

    private int totalTables;
    private int processedTables;

    private long totalTargetRecords;
    private long totalInsertedRecords;

    private String currentTable;
    private long currentTableInserted;
    private long currentTableTarget;
    private int currentTablePercent;

    private long startTime;
    private long endTime;
    private String message;

    @Builder.Default
    private List<Map<String, Object>> tableProgress = new ArrayList<>();

    @Builder.Default
    private Map<String, List<Map<String, Object>>> sampleDataReport = new ConcurrentHashMap<>();

    @Builder.Default
    private List<String> logs = new ArrayList<>();
}
