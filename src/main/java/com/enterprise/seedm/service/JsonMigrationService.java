package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JsonMigrationConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javafaker.Faker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class JsonMigrationService {

    private final FormatPreservingEncryptionService fpeService;
    private final ObjectMapper objectMapper;
    private final Faker faker;

    // In-memory store for async job progress
    private final Map<String, JsonMigrationProgress> progressMap = new ConcurrentHashMap<>();

    public JsonMigrationService(FormatPreservingEncryptionService fpeService, Faker faker) {
        this.fpeService = fpeService;
        this.objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.faker = faker;
    }

    public String processMigrationAsync(String executionId, JsonMigrationConfig config) {
        if (config == null || config.getSource() == null || config.getDest() == null || config.getRules() == null) {
            updateProgress(executionId, "FAILED", 0, 0, "JSON Migration configuration is missing or incomplete.");
            throw new IllegalStateException("JSON Migration configuration is missing or incomplete.");
        }

        Path sourceDir = Paths.get(config.getSource().getSourceDir());
        Path destDir = Paths.get(config.getDest().getDestDir());

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

            List<String> targetFiles = config.getRules().getTargetTables();
            List<Path> filesToProcess = new ArrayList<>();

            if (targetFiles != null && !targetFiles.isEmpty()) {
                for (String fileName : targetFiles) {
                    Path filePath = sourceDir.resolve(fileName);
                    if (Files.exists(filePath) && filePath.toString().endsWith(".json")) {
                        filesToProcess.add(filePath);
                    }
                }
            } else {
                Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".json")) {
                            filesToProcess.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            
            progress.setTotalFiles(filesToProcess.size());

            // Process each file
            for (Path file : filesToProcess) {
                processFile(file, sourceDir, destDir, config, executionId);
                progress.incrementProcessedFiles();
            }

            updateProgress(executionId, "COMPLETED", progress.getProcessedFiles(), progress.getTotalFiles(), null);
        } catch (Exception e) {
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
            
        } catch (IOException e) {
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
                
                if (childNode.isObject() || childNode.isArray()) {
                    maskNode(childNode, childPath, config);
                } else if (childNode.isValueNode()) {
                    String value = childNode.asText();
                    if (value != null && !value.isEmpty()) {
                        String newValue = applyRules(childPath, value, config);
                        if (newValue != null && !newValue.equals(value)) {
                            objectNode.put(fieldName, newValue);
                        }
                    }
                }
            });
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode childNode = arrayNode.get(i);
                if (childNode.isObject() || childNode.isArray()) {
                    maskNode(childNode, currentPath, config); 
                } else if (childNode.isValueNode()) {
                    String value = childNode.asText();
                    if (value != null && !value.isEmpty()) {
                        String newValue = applyRules(currentPath, value, config);
                        if (newValue != null && !newValue.equals(value)) {
                            if (childNode.isTextual()) arrayNode.set(i, arrayNode.textNode(newValue));
                            else if (childNode.isNumber()) arrayNode.set(i, arrayNode.numberNode(Long.parseLong(newValue)));
                            else arrayNode.set(i, arrayNode.textNode(newValue));
                        }
                    }
                }
            }
        }
    }

    private String applyRules(String fieldPath, String value, JsonMigrationConfig config) {
        JsonMigrationConfig.RulesConfig rules = config.getRules();
        if (rules.getMaskingColumns() != null && containsIgnoreCase(rules.getMaskingColumns(), fieldPath)) {
            return generateFakeData(fieldPath);
        } else if (rules.getPartialMaskingColumns() != null && containsIgnoreCase(rules.getPartialMaskingColumns(), fieldPath)) {
            return applyPartialMasking(value);
        } else if (rules.getConstraintFields() != null && containsIgnoreCase(rules.getConstraintFields(), fieldPath)) {
            try {
                return (String) fpeService.encrypt(value, "string");
            } catch (Exception e) {
                log.error("Failed to encrypt JSON field {}", fieldPath, e);
                return value;
            }
        }
        return value;
    }
    
    private boolean containsIgnoreCase(List<String> list, String target) {
        if (list == null) return false;
        for (String item : list) {
            if (item.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private String generateFakeData(String fieldPath) {
        String lower = fieldPath.toLowerCase();
        if (lower.contains("name")) {
            return faker.name().fullName();
        } else if (lower.contains("email")) {
            return faker.internet().emailAddress();
        } else if (lower.contains("phone")) {
            return faker.phoneNumber().phoneNumber();
        } else if (lower.contains("city")) {
            return faker.address().city();
        } else if (lower.contains("address")) {
            return faker.address().fullAddress();
        }
        return faker.lorem().word();
    }

    private String applyPartialMasking(String value) {
        if (value.length() <= 4) {
            return value.replaceAll(".", "*");
        }
        int maskCount = value.length() - 4;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maskCount; i++) {
            sb.append("*");
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

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> executions = new ArrayList<>();
        for (Map.Entry<String, JsonMigrationProgress> entry : progressMap.entrySet()) {
            Map<String, Object> execMap = new HashMap<>();
            execMap.put("id", entry.getKey());
            execMap.put("status", entry.getValue().getStatus());
            execMap.put("startTime", entry.getValue().getStartTime());
            executions.add(execMap);
        }
        return executions;
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