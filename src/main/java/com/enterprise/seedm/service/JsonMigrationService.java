package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JsonMigrationConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Service
@Slf4j
public class JsonMigrationService {

    private final JsonMaskingConfigService configService;
    private final ObjectMapper objectMapper;
    private final Faker faker;

    // Progress tracking map: executionId -> JsonMigrationProgress
    private final Map<String, JsonMigrationProgress> progressMap = new ConcurrentHashMap<>();

    public JsonMigrationService(JsonMaskingConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.faker = new Faker();
    }

    public String processMigrationAsync(String executionId) {
        JsonMigrationConfig config = configService.getConfig();
        if (config == null || config.getSourceDir() == null || config.getDestDir() == null) {
            updateProgress(executionId, "FAILED", 0, 0, "JSON Migration configuration is missing or incomplete.");
            throw new IllegalStateException("JSON Migration configuration is missing or incomplete.");
        }

        Path sourceDir = Paths.get(config.getSourceDir());
        Path destDir = Paths.get(config.getDestDir());

        if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
            updateProgress(executionId, "FAILED", 0, 0, "Source directory does not exist or is not a directory: " + sourceDir);
            throw new IllegalArgumentException("Source directory does not exist or is not a directory: " + sourceDir);
        }

        JsonMigrationProgress progress = progressMap.computeIfAbsent(executionId, k -> new JsonMigrationProgress());
        progress.setStatus("RUNNING");
        progress.setStartTime(System.currentTimeMillis());

        try {
            if (!Files.exists(destDir)) {
                Files.createDirectories(destDir);
            }

            List<Path> jsonFiles;
            try (Stream<Path> paths = Files.walk(sourceDir)) {
                jsonFiles = paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".json"))
                        .toList();
            }
            progress.setTotalFiles(jsonFiles.size());

            for (Path file : jsonFiles) {
                processFile(file, sourceDir, destDir, config, executionId);
                progress.incrementProcessedFiles();
            }
            progress.setStatus("COMPLETED");
            progress.setEndTime(System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Failed during JSON migration process", e);
            updateProgress(executionId, "FAILED", progress.getProcessedFiles(), progress.getTotalFiles(), "JSON migration failed: " + e.getMessage());
            throw new RuntimeException("JSON migration failed", e);
        }
        return executionId;
    }

    private void processFile(Path sourceFile, Path sourceBaseDir, Path destBaseDir, JsonMigrationConfig config, String executionId) {
        log.info("Processing file: {}", sourceFile);
        try {
            JsonNode rootNode = objectMapper.readTree(sourceFile.toFile());
            maskNode(rootNode, "", config);

            Path relativePath = sourceBaseDir.relativize(sourceFile);
            Path destFile = destBaseDir.resolve(relativePath);
            Files.createDirectories(destFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(destFile.toFile(), rootNode);
            log.info("Successfully migrated to: {}", destFile);
        } catch (Exception e) {
            log.error("Error processing file {}", sourceFile, e);
            updateProgress(executionId, "FAILED", progressMap.get(executionId).getProcessedFiles(), progressMap.get(executionId).getTotalFiles(), "Error processing file " + sourceFile.getFileName() + ": " + e.getMessage());
        }
    }

    private void maskNode(JsonNode node, String currentPath, JsonMigrationConfig config) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            objectNode.fieldNames().forEachRemaining(fieldName -> {
                JsonNode childNode = objectNode.get(fieldName);
                String childPath = currentPath.isEmpty() ? fieldName : currentPath + "." + fieldName;

                if (childNode.isValueNode()) {
                    if (config.getMaskingColumns().contains(childPath)) {
                        objectNode.set(fieldName, new TextNode(generateFakeData(fieldName, childNode.asText())));
                    } else if (config.getPartialMaskingColumns().contains(childPath)) {
                        objectNode.set(fieldName, new TextNode(applyPartialMasking(childNode.asText())));
                    }
                } else {
                    maskNode(childNode, childPath, config);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode arrayItem : node) {
                maskNode(arrayItem, currentPath, config);
            }
        }
    }

    private String generateFakeData(String fieldName, String originalValue) {
        String lowerCol = fieldName.toLowerCase();
        if (lowerCol.contains("email")) return faker.internet().emailAddress();
        if (lowerCol.contains("first_name") || lowerCol.contains("firstname")) return faker.name().firstName();
        if (lowerCol.contains("last_name") || lowerCol.contains("lastname")) return faker.name().lastName();
        if (lowerCol.contains("name")) return faker.name().fullName();
        if (lowerCol.contains("phone")) return faker.phoneNumber().cellPhone();
        if (lowerCol.contains("city")) return faker.address().city();
        if (lowerCol.contains("country")) return faker.address().country();
        if (lowerCol.contains("zip") || lowerCol.contains("postal")) return faker.address().zipCode();
        if (lowerCol.contains("address")) return faker.address().fullAddress();
        
        return faker.lorem().characters(8);
    }

    private String applyPartialMasking(String value) {
        if (value == null || value.length() <= 4) return value;
        int visibleCount = 4;
        int maskCount = value.length() - visibleCount;
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maskCount; i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c) || Character.isLetter(c)) {
                sb.append('X');
            } else {
                sb.append(c);
            }
        }
        sb.append(value.substring(maskCount));
        return sb.toString();
    }

    public Map<String, Object> getProgress(String executionId) {
        JsonMigrationProgress progress = progressMap.get(executionId);
        if (progress == null) {
            return Map.of("status", "NOT_FOUND");
        }
        return progress.toMap();
    }

    private void updateProgress(String executionId, String status, int processed, int total, String errorMessage) {
        JsonMigrationProgress progress = progressMap.computeIfAbsent(executionId, k -> new JsonMigrationProgress());
        progress.setStatus(status);
        progress.setProcessedFiles(processed);
        progress.setTotalFiles(total);
        progress.setErrorMessage(errorMessage);
        if ("FAILED".equals(status) || "COMPLETED".equals(status)) {
            progress.setEndTime(System.currentTimeMillis());
        }
    }

    // Inner class for progress tracking
    private static class JsonMigrationProgress {
        private String status = "PENDING";
        private AtomicInteger totalFiles = new AtomicInteger(0);
        private AtomicInteger processedFiles = new AtomicInteger(0);
        private String errorMessage;
        private Long startTime;
        private Long endTime;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getTotalFiles() { return totalFiles.get(); }
        public void setTotalFiles(int totalFiles) { this.totalFiles.set(totalFiles); }
        public int getProcessedFiles() { return processedFiles.get(); }
        public void incrementProcessedFiles() { this.processedFiles.incrementAndGet(); }
        public void setProcessedFiles(int processedFiles) { this.processedFiles.set(processedFiles); }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public Long getStartTime() { return startTime; }
        public void setStartTime(Long startTime) { this.startTime = startTime; }
        public Long getEndTime() { return endTime; }
        public void setEndTime(Long endTime) { this.endTime = endTime; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("status", status);
            map.put("totalFiles", totalFiles.get());
            map.put("processedFiles", processedFiles.get());
            map.put("errorMessage", errorMessage);
            map.put("startTime", startTime);
            map.put("endTime", endTime);
            return map;
        }
    }
}