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
    private final DataMaskingService dataMaskingService;

    // In-memory store for async job progress
    private final Map<String, JsonMigrationProgress> progressMap = new ConcurrentHashMap<>();

    public JsonMigrationService(FormatPreservingEncryptionService fpeService, Faker faker) {
        this(fpeService, faker, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JsonMigrationService(FormatPreservingEncryptionService fpeService, Faker faker, @org.springframework.context.annotation.Lazy DataMaskingService dataMaskingService) {
        this.fpeService = fpeService;
        this.objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.faker = faker;
        this.dataMaskingService = dataMaskingService;
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

    /**
     * Recursively masks a JSON tree supporting:
     * 1. primitive_array (array of values)
     * 2. nested_array (multi-dimensional array of values)
     * 3. empty_array (populating array with requested rule data)
     * 4. primitive key-value pairs with typed preservation
     */
    public JsonNode maskJsonTree(JsonNode rootNode, JsonMigrationConfig config) {
        if (rootNode != null && config != null) {
            maskNode(rootNode, "", config);
        }
        return rootNode;
    }

    private void maskNode(JsonNode node, String currentPath, JsonMigrationConfig config) {
        if (node == null || config == null || config.getRules() == null) {
            return;
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);

            for (String fieldName : fieldNames) {
                JsonNode childNode = objectNode.get(fieldName);
                String childPath = currentPath.isEmpty() ? fieldName : currentPath + "." + fieldName;

                if (childNode.isArray()) {
                    ArrayNode arrayNode = (ArrayNode) childNode;
                    if (isArrayOfObjects(arrayNode)) {
                        for (int i = 0; i < arrayNode.size(); i++) {
                            JsonNode element = arrayNode.get(i);
                            if (element.isObject() || element.isArray()) {
                                maskNode(element, childPath, config);
                            }
                        }
                    } else {
                        processArrayOfValues(arrayNode, childPath, config);
                    }
                } else if (childNode.isObject()) {
                    maskNode(childNode, childPath, config);
                } else if (childNode.isValueNode()) {
                    String value = childNode.isNull() ? null : childNode.asText();
                    String newValue = applyRules(childPath, value, config, childNode);
                    if ("__NULL__".equals(newValue)) {
                        objectNode.putNull(fieldName);
                    } else if (newValue != null && !newValue.equals(value)) {
                        putTypedValue(objectNode, fieldName, newValue, childNode);
                    }
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            if (isArrayOfObjects(arrayNode)) {
                for (int i = 0; i < arrayNode.size(); i++) {
                    JsonNode element = arrayNode.get(i);
                    if (element.isObject() || element.isArray()) {
                        maskNode(element, currentPath, config);
                    }
                }
            } else {
                processArrayOfValues(arrayNode, currentPath, config);
            }
        }
    }

    private boolean isArrayOfObjects(ArrayNode arrayNode) {
        if (!arrayNode.isEmpty()) {
            for (JsonNode child : arrayNode) {
                if (child.isObject()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isNestedArray(ArrayNode arrayNode, String fieldPath) {
        if (!arrayNode.isEmpty()) {
            for (JsonNode child : arrayNode) {
                if (child.isArray()) {
                    return true;
                }
            }
            return false;
        }
        String lower = fieldPath.toLowerCase();
        return lower.contains("nested") || lower.contains("matrix") || lower.contains("[][]") || lower.contains("[*][*]");
    }

    private void processArrayOfValues(ArrayNode arrayNode, String fieldPath, JsonMigrationConfig config) {
        JsonMigrationConfig.RulesConfig rules = config.getRules();
        boolean isNested = isNestedArray(arrayNode, fieldPath);

        // 1. NULL (nullifyColumns) -> create array of null value or nested array of null value
        if (rules.getNullifyColumns() != null && containsIgnoreCase(rules.getNullifyColumns(), fieldPath)) {
            if (isNested) {
                if (arrayNode.isEmpty()) {
                    ArrayNode sub1 = arrayNode.addArray();
                    sub1.addNull(); sub1.addNull();
                    ArrayNode sub2 = arrayNode.addArray();
                    sub2.addNull(); sub2.addNull();
                } else {
                    setAllLeavesToNull(arrayNode);
                }
            } else {
                if (arrayNode.isEmpty()) {
                    arrayNode.addNull();
                    arrayNode.addNull();
                } else {
                    for (int i = 0; i < arrayNode.size(); i++) {
                        arrayNode.set(i, arrayNode.nullNode());
                    }
                }
            }
            return;
        }

        // 2. FIXED DATA / DEFAULT VALUE (defaultValues) -> create array of fixed value or nested array of fixed value
        if (rules.getDefaultValues() != null) {
            String defVal = getMatchingDefaultValue(rules.getDefaultValues(), fieldPath);
            if (defVal != null) {
                if (isNested) {
                    if (arrayNode.isEmpty()) {
                        ArrayNode sub1 = arrayNode.addArray();
                        sub1.add(generateFixedLeafNode(null, defVal));
                        sub1.add(generateFixedLeafNode(null, defVal));
                        ArrayNode sub2 = arrayNode.addArray();
                        sub2.add(generateFixedLeafNode(null, defVal));
                        sub2.add(generateFixedLeafNode(null, defVal));
                    } else {
                        setAllLeavesToFixed(arrayNode, defVal);
                    }
                } else {
                    if (arrayNode.isEmpty()) {
                        arrayNode.add(generateFixedLeafNode(null, defVal));
                        arrayNode.add(generateFixedLeafNode(null, defVal));
                    } else {
                        for (int i = 0; i < arrayNode.size(); i++) {
                            arrayNode.set(i, generateFixedLeafNode(arrayNode.get(i), defVal));
                        }
                    }
                }
                return;
            }
        }

        // 3. CUSTOM METHOD FORMAT (customFormats) -> generate text or number based on input
        if (rules.getCustomFormats() != null) {
            String formatRule = getMatchingFormatRule(rules.getCustomFormats(), fieldPath);
            if (formatRule != null) {
                if (isNested) {
                    if (arrayNode.isEmpty()) {
                        ArrayNode sub1 = arrayNode.addArray();
                        sub1.add(generateCmfLeafNode(null, fieldPath, formatRule));
                        sub1.add(generateCmfLeafNode(null, fieldPath, formatRule));
                        ArrayNode sub2 = arrayNode.addArray();
                        sub2.add(generateCmfLeafNode(null, fieldPath, formatRule));
                        sub2.add(generateCmfLeafNode(null, fieldPath, formatRule));
                    } else {
                        setAllLeavesToCmf(arrayNode, fieldPath, formatRule);
                    }
                } else {
                    if (arrayNode.isEmpty()) {
                        arrayNode.add(generateCmfLeafNode(null, fieldPath, formatRule));
                        arrayNode.add(generateCmfLeafNode(null, fieldPath, formatRule));
                    } else {
                        for (int i = 0; i < arrayNode.size(); i++) {
                            arrayNode.set(i, generateCmfLeafNode(arrayNode.get(i), fieldPath, formatRule));
                        }
                    }
                }
                return;
            }
        }

        // 4. SFD (maskingColumns) -> generate array of faker data or nested array of faker data
        if (rules.getMaskingColumns() != null && containsIgnoreCase(rules.getMaskingColumns(), fieldPath)) {
            if (isNested) {
                if (arrayNode.isEmpty()) {
                    ArrayNode sub1 = arrayNode.addArray();
                    sub1.add(generateFakeLeafNode(null, fieldPath));
                    sub1.add(generateFakeLeafNode(null, fieldPath));
                    ArrayNode sub2 = arrayNode.addArray();
                    sub2.add(generateFakeLeafNode(null, fieldPath));
                    sub2.add(generateFakeLeafNode(null, fieldPath));
                } else {
                    setAllLeavesToFake(arrayNode, fieldPath);
                }
            } else {
                if (arrayNode.isEmpty()) {
                    arrayNode.add(generateFakeLeafNode(null, fieldPath));
                    arrayNode.add(generateFakeLeafNode(null, fieldPath));
                } else {
                    for (int i = 0; i < arrayNode.size(); i++) {
                        arrayNode.set(i, generateFakeLeafNode(arrayNode.get(i), fieldPath));
                    }
                }
            }
            return;
        }

        // 5. PMD (partialMaskingColumns)
        if (rules.getPartialMaskingColumns() != null && containsIgnoreCase(rules.getPartialMaskingColumns(), fieldPath)) {
            setAllLeavesToPartial(arrayNode);
            return;
        }

        // 6. FPH (constraintFields / constraintColumns)
        if ((rules.getConstraintFields() != null && containsIgnoreCase(rules.getConstraintFields(), fieldPath)) ||
            (rules.getConstraintColumns() != null && containsIgnoreCase(rules.getConstraintColumns(), fieldPath))) {
            setAllLeavesToFph(arrayNode, fieldPath, rules.getMaskingKey());
            return;
        }

        // If no direct rule matched the parent array, recurse on sub-arrays if present
        if (isNested) {
            for (JsonNode child : arrayNode) {
                if (child.isArray()) {
                    processArrayOfValues((ArrayNode) child, fieldPath, config);
                }
            }
        }
    }

    private void setAllLeavesToNull(ArrayNode arrayNode) {
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode child = arrayNode.get(i);
            if (child.isArray()) {
                setAllLeavesToNull((ArrayNode) child);
            } else {
                arrayNode.set(i, arrayNode.nullNode());
            }
        }
    }

    private void setAllLeavesToFixed(ArrayNode arrayNode, String defVal) {
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode child = arrayNode.get(i);
            if (child.isArray()) {
                setAllLeavesToFixed((ArrayNode) child, defVal);
            } else {
                arrayNode.set(i, generateFixedLeafNode(child, defVal));
            }
        }
    }

    private void setAllLeavesToCmf(ArrayNode arrayNode, String fieldPath, String formatRule) {
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode child = arrayNode.get(i);
            if (child.isArray()) {
                setAllLeavesToCmf((ArrayNode) child, fieldPath, formatRule);
            } else {
                arrayNode.set(i, generateCmfLeafNode(child, fieldPath, formatRule));
            }
        }
    }

    private void setAllLeavesToFake(ArrayNode arrayNode, String fieldPath) {
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode child = arrayNode.get(i);
            if (child.isArray()) {
                setAllLeavesToFake((ArrayNode) child, fieldPath);
            } else {
                arrayNode.set(i, generateFakeLeafNode(child, fieldPath));
            }
        }
    }

    private void setAllLeavesToPartial(ArrayNode arrayNode) {
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode child = arrayNode.get(i);
            if (child.isArray()) {
                setAllLeavesToPartial((ArrayNode) child);
            } else if (child.isValueNode() && !child.isNull()) {
                String masked = applyPartialMasking(child.asText());
                arrayNode.set(i, arrayNode.textNode(masked));
            }
        }
    }

    private void setAllLeavesToFph(ArrayNode arrayNode, String fieldPath, String key) {
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode child = arrayNode.get(i);
            if (child.isArray()) {
                setAllLeavesToFph((ArrayNode) child, fieldPath, key);
            } else if (child.isValueNode() && !child.isNull()) {
                try {
                    String enc;
                    if (key != null && !key.trim().isEmpty()) {
                        enc = (String) fpeService.encrypt(child.asText(), "string", key.trim());
                    } else {
                        enc = (String) fpeService.encrypt(child.asText(), "string");
                    }
                    arrayNode.set(i, arrayNode.textNode(enc));
                } catch (Exception e) {
                    log.error("Failed to encrypt JSON array element at {}", fieldPath, e);
                }
            }
        }
    }

    private JsonNode generateFakeLeafNode(JsonNode existingNode, String fieldPath) {
        if (existingNode != null) {
            if (existingNode.isInt()) {
                return objectMapper.getNodeFactory().numberNode(faker.number().numberBetween(10, 9999));
            } else if (existingNode.isLong()) {
                return objectMapper.getNodeFactory().numberNode(faker.number().numberBetween(1000L, 999999L));
            } else if (existingNode.isDouble() || existingNode.isFloat()) {
                return objectMapper.getNodeFactory().numberNode(faker.number().randomDouble(2, 10, 9999));
            } else if (existingNode.isBoolean()) {
                return objectMapper.getNodeFactory().booleanNode(faker.bool().bool());
            }
        }
        String lower = fieldPath.toLowerCase();
        if (lower.contains("int") || lower.contains("num") || lower.contains("count") || lower.contains("id")) {
            return objectMapper.getNodeFactory().numberNode(faker.number().numberBetween(10, 9999));
        } else if (lower.contains("float") || lower.contains("double") || lower.contains("score") || lower.contains("amount")) {
            return objectMapper.getNodeFactory().numberNode(faker.number().randomDouble(2, 10, 9999));
        } else if (lower.contains("bool")) {
            return objectMapper.getNodeFactory().booleanNode(faker.bool().bool());
        }
        return objectMapper.getNodeFactory().textNode(generateFakeData(fieldPath));
    }

    private JsonNode generateCmfLeafNode(JsonNode existingNode, String fieldPath, String formatRule) {
        if (dataMaskingService == null || formatRule == null) {
            return generateFakeLeafNode(existingNode, fieldPath);
        }
        Object seed = null;
        if (existingNode != null && !existingNode.isNull()) {
            if (existingNode.isNumber()) {
                seed = existingNode.numberValue();
            } else {
                seed = existingNode.asText();
            }
        } else {
            if (formatRule.toUpperCase().startsWith("NUM:")) {
                seed = 100;
            } else if (formatRule.toUpperCase().startsWith("DATE:")) {
                seed = "2026-01-01";
            } else {
                seed = "SAMPLE";
            }
        }
        Object res = dataMaskingService.applyCustomFormat(seed, formatRule);
        if (res instanceof Number num) {
            if (num instanceof Integer i) return objectMapper.getNodeFactory().numberNode(i);
            if (num instanceof Long l) return objectMapper.getNodeFactory().numberNode(l);
            if (num instanceof Double d) return objectMapper.getNodeFactory().numberNode(d);
            if (num instanceof Float f) return objectMapper.getNodeFactory().numberNode(f);
            if (num instanceof java.math.BigDecimal bd) return objectMapper.getNodeFactory().numberNode(bd);
            return objectMapper.getNodeFactory().numberNode(num.doubleValue());
        } else if (res != null) {
            String str = res.toString();
            if (formatRule.toUpperCase().startsWith("NUM:")) {
                try {
                    if (str.contains(".")) {
                        return objectMapper.getNodeFactory().numberNode(Double.parseDouble(str));
                    } else {
                        return objectMapper.getNodeFactory().numberNode(Long.parseLong(str));
                    }
                } catch (Exception ignored) {}
            }
            return objectMapper.getNodeFactory().textNode(str);
        }
        return objectMapper.getNodeFactory().nullNode();
    }

    private JsonNode generateFixedLeafNode(JsonNode existingNode, String defVal) {
        if (defVal == null || "null".equalsIgnoreCase(defVal.trim())) {
            return objectMapper.getNodeFactory().nullNode();
        }
        if ("true".equalsIgnoreCase(defVal.trim())) {
            return objectMapper.getNodeFactory().booleanNode(true);
        }
        if ("false".equalsIgnoreCase(defVal.trim())) {
            return objectMapper.getNodeFactory().booleanNode(false);
        }
        if (existingNode != null && existingNode.isNumber()) {
            try {
                if (defVal.contains(".")) {
                    return objectMapper.getNodeFactory().numberNode(Double.parseDouble(defVal));
                } else {
                    return objectMapper.getNodeFactory().numberNode(Long.parseLong(defVal));
                }
            } catch (Exception ignored) {}
        }
        return objectMapper.getNodeFactory().textNode(defVal);
    }

    private void putTypedValue(ObjectNode objectNode, String fieldName, String newValue, JsonNode originalNode) {
        if (originalNode.isBoolean()) {
            if ("true".equalsIgnoreCase(newValue) || "false".equalsIgnoreCase(newValue)) {
                objectNode.put(fieldName, Boolean.parseBoolean(newValue));
                return;
            }
        } else if (originalNode.isInt()) {
            try {
                objectNode.put(fieldName, Integer.parseInt(newValue));
                return;
            } catch (Exception ignored) {}
        } else if (originalNode.isLong()) {
            try {
                objectNode.put(fieldName, Long.parseLong(newValue));
                return;
            } catch (Exception ignored) {}
        } else if (originalNode.isDouble() || originalNode.isFloat()) {
            try {
                objectNode.put(fieldName, Double.parseDouble(newValue));
                return;
            } catch (Exception ignored) {}
        }
        objectNode.put(fieldName, newValue);
    }

    private String applyRules(String fieldPath, String value, JsonMigrationConfig config) {
        return applyRules(fieldPath, value, config, null);
    }

    private String applyRules(String fieldPath, String value, JsonMigrationConfig config, JsonNode originalNode) {
        JsonMigrationConfig.RulesConfig rules = config.getRules();
        if (rules.getNullifyColumns() != null && containsIgnoreCase(rules.getNullifyColumns(), fieldPath)) {
            return "__NULL__";
        }
        if (rules.getDefaultValues() != null) {
            String defVal = getMatchingDefaultValue(rules.getDefaultValues(), fieldPath);
            if (defVal != null) {
                return defVal;
            }
        }
        if (rules.getCustomFormats() != null) {
            String formatRule = getMatchingFormatRule(rules.getCustomFormats(), fieldPath);
            if (formatRule != null && dataMaskingService != null) {
                Object result = dataMaskingService.applyCustomFormat(value, formatRule);
                return result != null ? result.toString() : null;
            }
        }
        if (value == null) {
            return null;
        }
        if (rules.getMaskingColumns() != null && containsIgnoreCase(rules.getMaskingColumns(), fieldPath)) {
            if (originalNode != null) {
                if (originalNode.isBoolean()) {
                    return String.valueOf(faker.bool().bool());
                } else if (originalNode.isInt() || originalNode.isLong()) {
                    return String.valueOf(faker.number().numberBetween(1, 1000));
                } else if (originalNode.isDouble() || originalNode.isFloat()) {
                    return String.valueOf(faker.number().randomDouble(2, 1, 1000));
                }
            }
            String lower = fieldPath.toLowerCase();
            if (lower.contains("bool")) {
                return String.valueOf(faker.bool().bool());
            } else if (lower.contains("int") || lower.contains("count") || lower.contains("num")) {
                return String.valueOf(faker.number().numberBetween(1, 1000));
            } else if (lower.contains("float") || lower.contains("double") || lower.contains("score") || lower.contains("amount")) {
                return String.valueOf(faker.number().randomDouble(2, 1, 1000));
            }
            return generateFakeData(fieldPath);
        } else if (rules.getPartialMaskingColumns() != null && containsIgnoreCase(rules.getPartialMaskingColumns(), fieldPath)) {
            return applyPartialMasking(value);
        } else if ((rules.getConstraintFields() != null && containsIgnoreCase(rules.getConstraintFields(), fieldPath)) ||
                   (rules.getConstraintColumns() != null && containsIgnoreCase(rules.getConstraintColumns(), fieldPath))) {
            try {
                String key = rules.getMaskingKey();
                if (key != null && !key.trim().isEmpty()) {
                    return (String) fpeService.encrypt(value, "string", key.trim());
                } else {
                    return (String) fpeService.encrypt(value, "string");
                }
            } catch (Exception e) {
                log.error("Failed to encrypt JSON field {}", fieldPath, e);
                return value;
            }
        }
        return value;
    }

    private String getMatchingFormatRule(Map<String, String> customFormats, String fieldPath) {
        if (customFormats == null || customFormats.isEmpty() || fieldPath == null) return null;
        String cleanField = fieldPath.replaceAll("\\[\\*?\\]", "").trim();
        for (Map.Entry<String, String> entry : customFormats.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = entry.getKey().replaceAll("\\[\\*?\\]", "").trim();
            if (key.equalsIgnoreCase(cleanField) || key.endsWith("." + cleanField) || cleanField.endsWith("." + key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String getMatchingDefaultValue(Map<String, String> defaultValues, String fieldPath) {
        if (defaultValues == null || defaultValues.isEmpty() || fieldPath == null) return null;
        String cleanField = fieldPath.replaceAll("\\[\\*?\\]", "").trim();
        for (Map.Entry<String, String> entry : defaultValues.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = entry.getKey().replaceAll("\\[\\*?\\]", "").trim();
            if (key.equalsIgnoreCase(cleanField) || key.endsWith("." + cleanField) || cleanField.endsWith("." + key)) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    private boolean containsIgnoreCase(List<String> list, String target) {
        if (list == null || target == null) return false;
        String cleanTarget = target.replaceAll("\\[\\*?\\]", "").trim();
        for (String item : list) {
            if (item == null) continue;
            String cleanItem = item.replaceAll("\\[\\*?\\]", "").trim();
            if (cleanItem.equalsIgnoreCase(cleanTarget) ||
                cleanItem.endsWith("." + cleanTarget) ||
                cleanTarget.endsWith("." + cleanItem)) {
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
        } else if (lower.contains("company")) {
            return faker.company().name();
        } else if (lower.contains("country")) {
            return faker.address().country();
        } else if (lower.contains("zip") || lower.contains("postal")) {
            return faker.address().zipCode();
        } else if (lower.contains("tag") || lower.contains("skill")) {
            return faker.job().keySkills();
        } else if (lower.contains("role") || lower.contains("title")) {
            return faker.job().title();
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