package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CsvMigrationConfig;
import com.enterprise.seedm.model.CsvMigrationJob;
import com.enterprise.seedm.repository.CsvMigrationJobRepository;
import com.enterprise.seedm.util.CsvUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class CsvMigrationService {

    private final FormatPreservingEncryptionService fpeService;
    private final Faker faker;
    private final DataMaskingService dataMaskingService;
    private final CsvMigrationJobRepository jobRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, CsvMigrationProgress> progressMap = new ConcurrentHashMap<>();

    public CsvMigrationService(FormatPreservingEncryptionService fpeService, Faker faker) {
        this(fpeService, faker, null, null);
    }

    public CsvMigrationService(FormatPreservingEncryptionService fpeService, Faker faker, @org.springframework.context.annotation.Lazy DataMaskingService dataMaskingService) {
        this(fpeService, faker, dataMaskingService, null);
    }

    @Autowired
    public CsvMigrationService(FormatPreservingEncryptionService fpeService, Faker faker, @org.springframework.context.annotation.Lazy DataMaskingService dataMaskingService, CsvMigrationJobRepository jobRepository) {
        this.fpeService = fpeService;
        this.faker = faker != null ? faker : new Faker();
        this.dataMaskingService = dataMaskingService;
        this.jobRepository = jobRepository;
    }

    public String processMigrationAsync(String executionId, CsvMigrationConfig config) {
        if (config == null || config.getSource() == null || config.getDest() == null || config.getRules() == null) {
            updateProgress(executionId, "FAILED", 0, 0, "CSV Migration configuration is missing or incomplete.");
            throw new IllegalStateException("CSV Migration configuration is missing or incomplete.");
        }

        Path sourceDir = Paths.get(config.getSource().getSourceDir());
        Path destDir = Paths.get(config.getDest().getDestDir());

        if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
            updateProgress(executionId, "FAILED", 0, 0, "Source directory does not exist or is not a directory: " + sourceDir);
            throw new IllegalArgumentException("Source directory does not exist or is not a directory: " + sourceDir);
        }

        CsvMigrationProgress progress = progressMap.computeIfAbsent(executionId, k -> new CsvMigrationProgress());
        progress.setStatus("RUNNING");
        progress.setStartTime(System.currentTimeMillis());

        saveJobRecord(executionId, config, "RUNNING", null);

        try {
            if (!Files.exists(destDir)) {
                Files.createDirectories(destDir);
            }

            List<String> targetFiles = config.getRules().getTargetTables();
            List<Path> filesToProcess = new ArrayList<>();

            if (targetFiles != null && !targetFiles.isEmpty()) {
                for (String fileName : targetFiles) {
                    Path filePath = sourceDir.resolve(fileName);
                    if (Files.exists(filePath) && filePath.toString().toLowerCase().endsWith(".csv")) {
                        filesToProcess.add(filePath);
                    }
                }
            } else {
                Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().toLowerCase().endsWith(".csv")) {
                            filesToProcess.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            progress.setTotalFiles(filesToProcess.size());
            progress.tableProgress.clear();
            for (Path p : filesToProcess) {
                Map<String, Object> entry = new ConcurrentHashMap<>();
                entry.put("tableName", p.getFileName().toString());
                entry.put("readCount", 0);
                entry.put("writeCount", 0);
                entry.put("status", "PENDING");
                progress.tableProgress.add(entry);
            }
            updateProgress(executionId, "RUNNING", 0, filesToProcess.size(), null);

            for (Path file : filesToProcess) {
                processFile(file, sourceDir, destDir, config, executionId);
                progress.incrementProcessedFiles();
                updateProgress(executionId, "RUNNING", progress.getProcessedFiles(), progress.getTotalFiles(), null);
            }

            updateProgress(executionId, "COMPLETED", progress.getProcessedFiles(), progress.getTotalFiles(), null);
        } catch (Exception e) {
            updateProgress(executionId, "FAILED", progress.getProcessedFiles(), progress.getTotalFiles(), "CSV migration failed: " + e.getMessage());
            throw new RuntimeException("CSV migration failed", e);
        }
        return executionId;
    }

    private void processFile(Path sourceFile, Path sourceBaseDir, Path destBaseDir, CsvMigrationConfig config, String executionId) {
        log.info("Processing CSV file: {}", sourceFile);
        CsvMigrationProgress progress = progressMap.get(executionId);
        String fileName = sourceFile.getFileName().toString();

        try {
            List<String> header = CsvUtils.readHeader(sourceFile);
            if (header.isEmpty()) {
                log.warn("CSV file is empty: {}", sourceFile);
                return;
            }

            Path relativePath = sourceBaseDir.relativize(sourceFile);
            Path destFile = destBaseDir.resolve(relativePath);
            if (destFile.getParent() != null) {
                Files.createDirectories(destFile.getParent());
            }

            long rowCount = 0;
            try (BufferedReader br = Files.newBufferedReader(sourceFile, StandardCharsets.UTF_8);
                 BufferedWriter bw = Files.newBufferedWriter(destFile, StandardCharsets.UTF_8)) {

                // Read and write header line unchanged
                String headerLine = br.readLine();
                bw.write(headerLine);
                bw.newLine();

                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    List<String> values = CsvUtils.parseLine(line);
                    List<String> maskedValues = new ArrayList<>(values.size());

                    for (int i = 0; i < values.size(); i++) {
                        String colName = (i < header.size()) ? header.get(i).trim() : ("col_" + i);
                        String rawVal = values.get(i);
                        String maskedVal = applyRules(fileName, colName, rawVal, config);
                        maskedValues.add(maskedVal);
                    }

                    bw.write(CsvUtils.formatLine(maskedValues));
                    bw.newLine();
                    rowCount++;
                }
            }

            if (progress != null) {
                progress.addRecords(rowCount);
                for (Map<String, Object> map : progress.tableProgress) {
                    if (fileName.equals(map.get("tableName"))) {
                        map.put("readCount", rowCount);
                        map.put("writeCount", rowCount);
                        map.put("status", "COMPLETED");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to process CSV file {}: {}", sourceFile, e.getMessage(), e);
            throw new RuntimeException("Failed processing CSV: " + fileName, e);
        }
    }

    public String applyRules(String fileName, String colName, String value, CsvMigrationConfig config) {
        if (value == null || config == null || config.getRules() == null) {
            return value;
        }

        CsvMigrationConfig.RulesConfig rules = config.getRules();
        String fullPath = fileName + "." + colName;

        // 1. NULL check
        if (rules.getNullifyColumns() != null &&
                (rules.getNullifyColumns().contains(fullPath) || rules.getNullifyColumns().contains(colName))) {
            return "";
        }

        // 2. Default value check
        if (rules.getDefaultValues() != null) {
            if (rules.getDefaultValues().containsKey(fullPath)) return rules.getDefaultValues().get(fullPath);
            if (rules.getDefaultValues().containsKey(colName)) return rules.getDefaultValues().get(colName);
        }

        // 3. Custom format (CMF)
        if (rules.getCustomFormats() != null) {
            String formatRule = rules.getCustomFormats().get(fullPath);
            if (formatRule == null) formatRule = rules.getCustomFormats().get(colName);
            if (formatRule != null) {
                return applyCmf(value, formatRule);
            }
        }

        // 4. Partial masking (PMD)
        if (rules.getPartialMaskingColumns() != null &&
                (rules.getPartialMaskingColumns().contains(fullPath) || rules.getPartialMaskingColumns().contains(colName))) {
            return applyPartialMask(value);
        }

        // 5. Format-Preserving Encryption / Hash (FPE / Constraint)
        if ((rules.getConstraintColumns() != null && (rules.getConstraintColumns().contains(fullPath) || rules.getConstraintColumns().contains(colName))) ||
                (rules.getConstraintFields() != null && (rules.getConstraintFields().contains(fullPath) || rules.getConstraintFields().contains(colName)))) {
            String key = (rules.getMaskingKey() != null && !rules.getMaskingKey().trim().isEmpty())
                    ? rules.getMaskingKey().trim()
                    : "DefaultKey123";
            try {
                if (fpeService != null) {
                    Object enc = fpeService.encrypt(value, "VARCHAR", key);
                    if (enc != null) return enc.toString();
                }
            } catch (Exception e) {
                log.warn("FPE failed for {}, fallback to hash", fullPath, e);
            }
            return "ENC_" + Math.abs(value.hashCode() ^ key.hashCode());
        }

        // 6. Synthetic Fake Data (SFD)
        if (rules.getMaskingColumns() != null &&
                (rules.getMaskingColumns().contains(fullPath) || rules.getMaskingColumns().contains(colName))) {
            return generateFakeValue(colName, value);
        }

        return value;
    }

    private String applyPartialMask(String value) {
        if (value.length() <= 3) {
            return "*".repeat(value.length());
        }
        int keep = Math.min(2, value.length() / 4);
        String prefix = value.substring(0, keep);
        String suffix = value.substring(value.length() - keep);
        int maskedLen = value.length() - (keep * 2);
        return prefix + "*".repeat(maskedLen) + suffix;
    }

    private String applyCmf(String value, String formatRule) {
        StringBuilder sb = new StringBuilder();
        for (char c : formatRule.toCharArray()) {
            if (c == '#') sb.append(faker.number().randomDigit());
            else if (c == 'X') sb.append((char) ('A' + faker.number().numberBetween(0, 26)));
            else sb.append(c);
        }
        return sb.toString();
    }

    private String generateFakeValue(String colName, String originalValue) {
        String lower = colName.toLowerCase();
        if (lower.contains("email")) return faker.internet().emailAddress();
        if (lower.contains("name")) return faker.name().fullName();
        if (lower.contains("phone")) return faker.phoneNumber().cellPhone();
        if (lower.contains("address")) return faker.address().streetAddress();
        if (lower.contains("city")) return faker.address().city();
        if (lower.contains("zip") || lower.contains("postal")) return faker.address().zipCode();
        if (lower.contains("country")) return faker.address().country();
        if (lower.contains("company")) return faker.company().name();
        return faker.lorem().word();
    }

    public Map<String, Object> getProgress(String executionId) {
        CsvMigrationProgress progress = progressMap.get(executionId);
        if (progress != null) {
            Map<String, Object> map = new HashMap<>();
            map.put("executionId", executionId);
            map.put("status", progress.getStatus());
            map.put("errorMessage", progress.getErrorMessage());
            map.put("startTime", progress.getStartTime());
            map.put("endTime", progress.getEndTime());
            map.put("totalFiles", progress.getTotalFiles());
            map.put("processedFiles", progress.getProcessedFiles());
            map.put("totalTables", progress.getTotalFiles());
            map.put("processedTables", progress.getProcessedFiles());
            map.put("totalRecords", progress.getTotalRecords().get());
            map.put("recordsInserted", progress.getTotalRecords().get());
            map.put("recordsRead", progress.getTotalRecords().get());
            map.put("tableProgress", new ArrayList<>(progress.tableProgress));

            List<String> completed = new ArrayList<>();
            for (Map<String, Object> tp : progress.tableProgress) {
                if ("COMPLETED".equals(tp.get("status"))) {
                    completed.add(tp.get("tableName").toString());
                }
            }
            map.put("completedTables", completed);
            long end = progress.getEndTime() > 0 ? progress.getEndTime() : System.currentTimeMillis();
            map.put("elapsedSeconds", (end - progress.getStartTime()) / 1000);
            return map;
        }

        if (jobRepository == null) {
            return Map.of("status", "NOT_FOUND");
        }
        CsvMigrationJob job = jobRepository.findByExecutionId(executionId);
        if (job == null) {
            return Map.of("status", "NOT_FOUND");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("executionId", job.getExecutionId());
        map.put("jobName", job.getJobName());
        map.put("status", job.getStatus());
        map.put("errorMessage", job.getErrorMessage());
        map.put("startTime", job.getCreatedAt());
        map.put("endTime", job.getCompletedAt());
        map.put("totalFiles", job.getTotalFiles() != null ? job.getTotalFiles() : 0);
        map.put("processedFiles", job.getProcessedFiles() != null ? job.getProcessedFiles() : 0);
        map.put("totalTables", job.getTotalFiles() != null ? job.getTotalFiles() : 0);
        map.put("processedTables", job.getProcessedFiles() != null ? job.getProcessedFiles() : 0);
        map.put("totalRecords", job.getTotalRecords() != null ? job.getTotalRecords() : 0L);

        List<Map<String, Object>> fileProgress = new ArrayList<>();
        if (job.getProgressDetails() != null && !job.getProgressDetails().isBlank()) {
            try {
                fileProgress = objectMapper.readValue(job.getProgressDetails(), List.class);
            } catch (Exception ignored) {}
        }
        map.put("tableProgress", fileProgress);
        return map;
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> executions = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (Map.Entry<String, CsvMigrationProgress> entry : progressMap.entrySet()) {
            Map<String, Object> execMap = new HashMap<>();
            execMap.put("id", entry.getKey());
            execMap.put("jobName", entry.getKey());
            execMap.put("status", entry.getValue().getStatus());
            execMap.put("startTime", entry.getValue().getStartTime());
            executions.add(execMap);
            seenIds.add(entry.getKey());
        }

        if (jobRepository != null) {
            for (CsvMigrationJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
                if (!seenIds.contains(job.getExecutionId())) {
                    Map<String, Object> execMap = new HashMap<>();
                    execMap.put("id", job.getExecutionId());
                    execMap.put("jobName", job.getJobName());
                    execMap.put("status", job.getStatus());
                    execMap.put("startTime", job.getCreatedAt());
                    executions.add(execMap);
                    seenIds.add(job.getExecutionId());
                }
            }
        }
        return executions;
    }

    private void updateProgress(String executionId, String status, int processed, int total, String errorMessage) {
        CsvMigrationProgress progress = progressMap.get(executionId);
        if (progress != null) {
            progress.setStatus(status);
            progress.setProcessedFiles(processed);
            progress.setTotalFiles(total);
            progress.setErrorMessage(errorMessage);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                progress.setEndTime(System.currentTimeMillis());
            }
        }
        saveJobRecord(executionId, null, status, errorMessage);
    }

    private void saveJobRecord(String executionId, CsvMigrationConfig config, String status, String errorMessage) {
        if (jobRepository == null) return;
        try {
            CsvMigrationJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new CsvMigrationJob();
                job.setExecutionId(executionId);
                job.setJobName(executionId);
                job.setCreatedAt(System.currentTimeMillis());
            }
            job.setStatus(status);
            job.setErrorMessage(errorMessage);
            CsvMigrationProgress progress = progressMap.get(executionId);
            if (progress != null) {
                job.setTotalFiles(progress.getTotalFiles());
                job.setProcessedFiles(progress.getProcessedFiles());
                job.setTotalRecords(progress.getTotalRecords().get());
                if (!progress.tableProgress.isEmpty()) {
                    job.setProgressDetails(objectMapper.writeValueAsString(progress.tableProgress));
                }
            }
            if (config != null) {
                job.setConfigDetails(objectMapper.writeValueAsString(config));
            }
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                job.setCompletedAt(System.currentTimeMillis());
            }
            jobRepository.saveAndFlush(job);
        } catch (Exception e) {
            log.error("Failed to save CsvMigrationJob", e);
        }
    }

    @Data
    public static class CsvMigrationProgress {
        private String status = "PENDING";
        private int totalFiles = 0;
        private int processedFiles = 0;
        private final AtomicLong totalRecords = new AtomicLong(0);
        private String errorMessage;
        private long startTime;
        private long endTime;
        private final List<Map<String, Object>> tableProgress = Collections.synchronizedList(new ArrayList<>());

        public void incrementProcessedFiles() {
            this.processedFiles++;
        }

        public void addRecords(long count) {
            this.totalRecords.addAndGet(count);
        }
    }
}
