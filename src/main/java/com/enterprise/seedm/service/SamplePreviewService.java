package com.enterprise.seedm.service;

import com.enterprise.seedm.model.SamplePreviewRequest;
import com.enterprise.seedm.model.SamplePreviewResponse;
import com.enterprise.seedm.model.AiPiiDetectionResponse.PiiEntityInfo;
import com.enterprise.seedm.util.CsvUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        boolean fileOrNoSql = isFileOrNoSqlSource(tableName, request.getJobType());
        boolean isCsv = isCsvSource(tableName, request.getJobType());

        // 1. Fetch Columns
        List<String> columns = null;
        if (request.getColumns() != null && !request.getColumns().isEmpty()) {
            columns = new ArrayList<>(request.getColumns());
        }
        if (columns == null || columns.isEmpty()) {
            if (isCsv) {
                columns = extractCsvHeaders(tableName, request.getSourcePath());
            } else if (!fileOrNoSql) {
                try {
                    columns = tableDiscoveryService.getTableColumns(tableName);
                } catch (Exception e) {
                    log.warn("Could not retrieve columns for table {}: {}", tableName, e.getMessage());
                }
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
        Set<String> nullifyCols = toLowerSet(request.getNullifyColumns(), tableName);
        Map<String, String> defaultVals = normalizeDefaultValues(request.getDefaultValues(), tableName);
        Map<String, String> customFormatRules = normalizeDefaultValues(request.getCustomFormats(), tableName);

        int maskedCount = 0;
        for (String col : columns) {
            String lowerCol = col.toLowerCase();
            if (nullifyCols.contains(lowerCol)) {
                columnRules.put(col, "NULL");
                maskedCount++;
            } else if (defaultVals.containsKey(lowerCol)) {
                columnRules.put(col, "DEFAULT");
                maskedCount++;
            } else if (customFormatRules.containsKey(lowerCol)) {
                columnRules.put(col, "CMF");
                maskedCount++;
            } else if (constraintCols.contains(lowerCol)) {
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
        List<Map<String, Object>> rawRows = null;
        if (isCsv) {
            rawRows = readCsvSampleRows(tableName, request.getSourcePath(), columns, limit);
        } else if (!fileOrNoSql) {
            try {
                rawRows = tableDiscoveryService.getSampleRows(tableName, limit);
            } catch (Exception e) {
                log.warn("Could not query sample rows for {}: {}", tableName, e.getMessage());
            }
        }
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

        // 5. Construct 'After' view (Applying synthetic Faker, DateShift, FPH, Nullify, Default, or CMF)
        List<Map<String, Object>> afterRows = new ArrayList<>();
        for (Map<String, Object> row : rawRows) {
            Map<String, Object> masked = dataMaskingService.maskDataWithRules(
                    tableName,
                    row,
                    request.getMaskingColumns(),
                    request.getConstraintColumns(),
                    request.getPartialMaskingColumns(),
                    request.getNullifyColumns(),
                    request.getDefaultValues(),
                    request.getCustomFormats(),
                    request.getMaskingKey()
            );

            Map<String, Object> afterRow = new LinkedHashMap<>();
            for (String col : columns) {
                Object val = masked.get(col);
                afterRow.put(col, val != null ? val : "NULL");
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

    private Map<String, String> normalizeDefaultValues(Map<String, String> defaults, String tableName) {
        Map<String, String> map = new HashMap<>();
        if (defaults == null) return map;
        String prefix = tableName != null ? tableName.toLowerCase() + "." : "";
        for (Map.Entry<String, String> e : defaults.entrySet()) {
            if (e.getKey() == null) continue;
            String clean = e.getKey().trim().toLowerCase();
            if (!prefix.isEmpty() && clean.startsWith(prefix)) {
                map.put(clean.substring(prefix.length()), e.getValue());
            } else if (!clean.contains(".")) {
                map.put(clean, e.getValue());
            }
        }
        return map;
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

    private boolean isCsvSource(String tableName, String jobType) {
        if (tableName != null && tableName.toLowerCase().endsWith(".csv")) return true;
        return "csv".equalsIgnoreCase(jobType);
    }

    private boolean isFileOrNoSqlSource(String tableName, String jobType) {
        if (tableName != null) {
            String lower = tableName.toLowerCase();
            if (lower.endsWith(".csv") || lower.endsWith(".json")) return true;
        }
        return "csv".equalsIgnoreCase(jobType) || "json".equalsIgnoreCase(jobType) || "mongo".equalsIgnoreCase(jobType);
    }

    private Path resolveCsvPath(String tableName, String sourcePath) {
        if (sourcePath != null && !sourcePath.isBlank()) {
            Path p = Paths.get(sourcePath.trim());
            if (Files.isRegularFile(p)) {
                return p;
            }
            if (Files.isDirectory(p)) {
                Path candidate = p.resolve(tableName);
                if (Files.exists(candidate)) return candidate;
            }
        }
        Path direct = Paths.get(tableName);
        if (Files.exists(direct)) return direct;
        Path inData = Paths.get("data", tableName);
        if (Files.exists(inData)) return inData;
        return null;
    }

    private List<String> extractCsvHeaders(String tableName, String sourcePath) {
        try {
            Path target = resolveCsvPath(tableName, sourcePath);
            if (target != null && Files.exists(target)) {
                List<String> headers = CsvUtils.readHeader(target);
                if (headers != null && !headers.isEmpty()) {
                    return headers;
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract CSV headers directly from file: {}", e.getMessage());
        }
        return null;
    }

    private List<Map<String, Object>> readCsvSampleRows(String tableName, String sourcePath, List<String> columns, int limit) {
        try {
            Path target = resolveCsvPath(tableName, sourcePath);
            if (target != null && Files.exists(target)) {
                try (BufferedReader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
                    String headerLine = reader.readLine();
                    if (headerLine != null) {
                        List<String> fileHeaders = CsvUtils.parseLine(headerLine);
                        List<Map<String, Object>> rows = new ArrayList<>();
                        String line;
                        while ((line = reader.readLine()) != null && rows.size() < limit) {
                            if (line.trim().isEmpty()) continue;
                            List<String> values = CsvUtils.parseLine(line);
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 0; i < fileHeaders.size(); i++) {
                                String col = fileHeaders.get(i);
                                String val = i < values.size() ? values.get(i) : "";
                                row.put(col, val);
                            }
                            rows.add(row);
                        }
                        if (!rows.isEmpty()) {
                            return rows;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not read local CSV sample rows for {}: {}", tableName, e.getMessage());
        }
        return null;
    }
}

