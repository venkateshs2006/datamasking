package com.enterprise.seedm.service;

import com.enterprise.seedm.model.SamplePreviewRequest;
import com.enterprise.seedm.model.SamplePreviewResponse;
import com.enterprise.seedm.model.AiPiiDetectionResponse.PiiEntityInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class SamplePreviewService {

    private final TableDiscoveryService tableDiscoveryService;
    private final DataMaskingService dataMaskingService;
    private final AiPiiDetectorService aiPiiDetectorService;

    /**
     * Generate before vs. after sample anonymization preview for a specific table.
     * Ensures raw PII values are redacted in the 'before' view for zero-exposure security.
     */
    public SamplePreviewResponse generatePreview(SamplePreviewRequest request) {
        String tableName = request.getTableName();
        if ((tableName == null || tableName.isBlank()) && request.getTables() != null && !request.getTables().isEmpty()) {
            tableName = request.getTables().get(0);
        }

        if (tableName == null || tableName.isBlank()) {
            return SamplePreviewResponse.builder()
                    .message("No table specified for preview.")
                    .build();
        }

        int limit = request.getLimit() > 0 ? Math.min(request.getLimit(), 10) : 5;

        // 1. Fetch Columns
        List<String> columns = null;
        if (request.getColumns() != null && !request.getColumns().isEmpty()) {
            columns = new ArrayList<>(request.getColumns());
        }
        if (columns == null || columns.isEmpty()) {
            try {
                columns = tableDiscoveryService.getTableColumns(tableName);
            } catch (Exception e) {
                log.warn("Could not retrieve columns for table {}: {}", tableName, e.getMessage());
            }
        }

        if (columns == null || columns.isEmpty()) {
            columns = List.of("id", "name", "email", "created_at");
        }

        // 2. Classify Column Rules
        Map<String, String> columnRules = new LinkedHashMap<>();
        Set<String> maskingCols = toLowerSet(request.getMaskingColumns(), tableName);
        Set<String> partialCols = toLowerSet(request.getPartialMaskingColumns(), tableName);
        Set<String> constraintCols = toLowerSet(request.getConstraintColumns(), tableName);

        int maskedCount = 0;
        for (String col : columns) {
            String lowerCol = col.toLowerCase();
            if (constraintCols.contains(lowerCol)) {
                columnRules.put(col, "FPH");
                maskedCount++;
            } else if (partialCols.contains(lowerCol)) {
                columnRules.put(col, "PMD");
                maskedCount++;
            } else if (maskingCols.contains(lowerCol)) {
                columnRules.put(col, "SFD");
                maskedCount++;
            } else {
                // Heuristic check to ensure unconfigured PII is also safeguarded
                PiiEntityInfo info = aiPiiDetectorService.analyzeColumn(tableName, col);
                if (info != null && info.getRuleType() != null) {
                    columnRules.put(col, info.getRuleType().toUpperCase());
                    maskedCount++;
                } else {
                    columnRules.put(col, "NONE");
                }
            }
        }

        // 3. Query sample rows or generate realistic fallbacks
        List<Map<String, Object>> rawRows = tableDiscoveryService.getSampleRows(tableName, limit);
        if (rawRows == null || rawRows.isEmpty()) {
            rawRows = generateSyntheticBaseRows(tableName, columns, limit);
        }

        // 4. Construct 'Before' view (Redacting sensitive PII columns)
        List<Map<String, Object>> beforeRows = new ArrayList<>();
        for (Map<String, Object> row : rawRows) {
            Map<String, Object> beforeRow = new LinkedHashMap<>();
            for (String col : columns) {
                Object val = row.get(col);
                String rule = columnRules.getOrDefault(col, "NONE");

                if (!"NONE".equalsIgnoreCase(rule)) {
                    // Zero-exposure protection: Redact real PII value
                    beforeRow.put(col, "•••••••• [" + rule + " PROTECTED]");
                } else {
                    beforeRow.put(col, val != null ? val : "N/A");
                }
            }
            beforeRows.add(beforeRow);
        }

        // 5. Construct 'After' view (Applying synthetic Faker, DateShift, or FPH)
        List<Map<String, Object>> afterRows = new ArrayList<>();
        for (Map<String, Object> row : rawRows) {
            Map<String, Object> masked = dataMaskingService.maskDataWithRules(
                    tableName,
                    row,
                    request.getMaskingColumns(),
                    request.getConstraintColumns(),
                    request.getPartialMaskingColumns()
            );

            Map<String, Object> afterRow = new LinkedHashMap<>();
            for (String col : columns) {
                Object val = masked.get(col);
                afterRow.put(col, val != null ? val : "N/A");
            }
            afterRows.add(afterRow);
        }

        return SamplePreviewResponse.builder()
                .tableName(tableName)
                .columns(columns)
                .columnRules(columnRules)
                .beforeRows(beforeRows)
                .afterRows(afterRows)
                .rowCount(rawRows.size())
                .maskedColumnCount(maskedCount)
                .message("Successfully generated sample anonymization preview.")
                .build();
    }

    private Set<String> toLowerSet(List<String> list, String currentTable) {
        Set<String> set = new HashSet<>();
        if (list == null) return set;
        String prefix = currentTable.toLowerCase() + ".";
        for (String item : list) {
            if (item == null) continue;
            String clean = item.trim().toLowerCase();
            if (clean.startsWith(prefix)) {
                set.add(clean.substring(prefix.length()));
            } else if (!clean.contains(".")) {
                set.add(clean);
            }
        }
        return set;
    }

    private List<Map<String, Object>> generateSyntheticBaseRows(String tableName, List<String> columns, int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String col : columns) {
                String c = col.toLowerCase();
                if (c.equals("id") || c.endsWith("_id")) {
                    row.put(col, 1000 + i);
                } else if (c.contains("email")) {
                    row.put(col, "user" + i + "@enterprise.com");
                } else if (c.contains("first_name") || c.contains("fname")) {
                    row.put(col, "John");
                } else if (c.contains("last_name") || c.contains("lname")) {
                    row.put(col, "Doe");
                } else if (c.contains("name")) {
                    row.put(col, "User " + i);
                } else if (c.contains("phone")) {
                    row.put(col, "+1-555-019" + i);
                } else if (c.contains("address") || c.contains("street")) {
                    row.put(col, (100 + i) + " Main Boulevard");
                } else if (c.contains("city")) {
                    row.put(col, "New York");
                } else if (c.contains("zip") || c.contains("postal")) {
                    row.put(col, "1000" + i);
                } else if (c.contains("country")) {
                    row.put(col, "United States");
                } else if (c.contains("card") || c.contains("pan")) {
                    row.put(col, "4111-2222-3333-444" + i);
                } else if (c.contains("ssn")) {
                    row.put(col, "987-65-432" + i);
                } else if (c.contains("salary") || c.contains("amount") || c.contains("balance")) {
                    row.put(col, 5000.00 + (i * 250));
                } else if (c.contains("dob") || c.contains("birth")) {
                    row.put(col, LocalDate.of(1985 + (i % 15), (i % 12) + 1, (i % 28) + 1).toString());
                } else if (c.contains("status")) {
                    row.put(col, "ACTIVE");
                } else if (c.contains("active") || c.startsWith("is_")) {
                    row.put(col, true);
                } else {
                    row.put(col, "Sample " + col + " " + i);
                }
            }
            rows.add(row);
        }
        return rows;
    }
}
