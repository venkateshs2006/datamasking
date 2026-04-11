package com.enterprise.seedm.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/fs")
@Slf4j
public class FileSystemController {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Faker faker = new Faker();

    @PostMapping("/scan")
    public ResponseEntity<?> scanDirectory(@RequestBody Map<String, String> request) {
        String dirPath = request.get("path");
        if (dirPath == null || dirPath.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path is required"));
        }

        try {
            Path path = Paths.get(dirPath);
            if (!Files.exists(path)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Directory does not exist"));
            }
            if (!Files.isDirectory(path)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Path is not a directory"));
            }

            long jsonFileCount;
            Path sampleFile = null;

            try (Stream<Path> walk = Files.walk(path)) {
                List<Path> jsonFiles = walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".json"))
                        .toList();
                
                jsonFileCount = jsonFiles.size();
                if (jsonFileCount > 0) {
                    sampleFile = jsonFiles.get(0);
                }
            }

            Set<String> jsonKeys = new HashSet<>();
            if (sampleFile != null) {
                try {
                    JsonNode rootNode = objectMapper.readTree(sampleFile.toFile());
                    extractKeys("", rootNode, jsonKeys);
                } catch (Exception e) {
                    log.warn("Failed to parse sample JSON file for keys: {}", sampleFile, e);
                }
            }

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "path", dirPath,
                    "fileCount", jsonFileCount,
                    "sampleKeys", new ArrayList<>(jsonKeys)
            ));

        } catch (Exception e) {
            log.error("Error scanning directory: {}", dirPath, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to scan directory: " + e.getMessage()));
        }
    }

    private void extractKeys(String currentPath, JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String newPath = currentPath.isEmpty() ? field.getKey() : currentPath + "." + field.getKey();
                keys.add(newPath);
                extractKeys(newPath, field.getValue(), keys);
            }
        } else if (node.isArray()) {
            for (JsonNode arrayItem : node) {
                extractKeys(currentPath, arrayItem, keys);
            }
        }
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generateDummyJson(@RequestBody Map<String, Object> request) {
        try {
            String templatePath = (String) request.get("templateFile");
            String outputDirPath = (String) request.get("outputDir");
            Integer count = (Integer) request.get("count");

            if (templatePath == null || outputDirPath == null || count == null || count <= 0) {
                return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Invalid parameters"));
            }

            Path template = Paths.get(templatePath);
            Path outDir = Paths.get(outputDirPath);

            if (!Files.exists(template) || !Files.isRegularFile(template)) {
                return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Template file not found"));
            }

            if (!Files.exists(outDir)) {
                Files.createDirectories(outDir);
            }

            JsonNode rootNode = objectMapper.readTree(template.toFile());

            for (int i = 1; i <= count; i++) {
                JsonNode dummyNode = generateFakeNode(rootNode, "");
                String fileName = "dummy_data_" + UUID.randomUUID().toString().substring(0, 8) + "_" + i + ".json";
                Path outFilePath = outDir.resolve(fileName);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(outFilePath.toFile(), dummyNode);
            }

            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Generated " + count + " files successfully."));

        } catch (Exception e) {
            log.error("Error generating dummy JSON", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    private JsonNode generateFakeNode(JsonNode originalNode, String fieldName) {
        if (originalNode.isObject()) {
            ObjectNode newObj = objectMapper.createObjectNode();
            originalNode.fields().forEachRemaining(entry -> {
                newObj.set(entry.getKey(), generateFakeNode(entry.getValue(), entry.getKey()));
            });
            return newObj;
        } else if (originalNode.isArray()) {
            ArrayNode newArr = objectMapper.createArrayNode();
            for (JsonNode item : originalNode) {
                newArr.add(generateFakeNode(item, fieldName));
            }
            return newArr;
        } else if (originalNode.isTextual()) {
            return new TextNode(generateFakeString(fieldName, originalNode.asText()));
        } else if (originalNode.isNumber()) {
            return new IntNode(faker.number().numberBetween(1, 10000));
        } else if (originalNode.isBoolean()) {
            return BooleanNode.valueOf(faker.bool().bool());
        }
        return originalNode.deepCopy();
    }

    private String generateFakeString(String fieldName, String originalValue) {
        if (originalValue != null) {
            if (originalValue.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                return new java.sql.Date(faker.date().birthday().getTime()).toString();
            } else if (originalValue.matches("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*")) {
                return new java.sql.Timestamp(faker.date().birthday().getTime()).toString();
            } else if (originalValue.matches("^\\d{2}/\\d{2}/\\d{4}$")) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(faker.date().birthday());
                return String.format("%02d/%02d/%04d", cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.YEAR));
            }
        }

        String lowerCol = fieldName == null ? "" : fieldName.toLowerCase();
        
        if (lowerCol.contains("email")) return faker.internet().emailAddress();
        if (lowerCol.contains("first_name") || lowerCol.contains("firstname")) return faker.name().firstName();
        if (lowerCol.contains("last_name") || lowerCol.contains("lastname")) return faker.name().lastName();
        if (lowerCol.contains("name")) return faker.name().fullName();
        if (lowerCol.contains("phone")) return faker.phoneNumber().cellPhone();
        if (lowerCol.contains("city")) return faker.address().city();
        if (lowerCol.contains("country")) return faker.address().country();
        if (lowerCol.contains("zip") || lowerCol.contains("postal")) return faker.address().zipCode();
        if (lowerCol.contains("address")) return faker.address().fullAddress();
        if (lowerCol.contains("id") || lowerCol.contains("uuid")) return UUID.randomUUID().toString();
        
        if (lowerCol.contains("date") || lowerCol.contains("timestamp") || lowerCol.contains("dob") || lowerCol.contains("time") || lowerCol.contains("created") || lowerCol.contains("updated")) {
             return new java.sql.Timestamp(faker.date().birthday().getTime()).toString();
        }

        return faker.lorem().word();
    }
}