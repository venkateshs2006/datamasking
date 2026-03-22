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
import java.util.stream.Stream;

@Service
@Slf4j
public class JsonMigrationService {

    private final JsonMaskingConfigService configService;
    private final ObjectMapper objectMapper;
    private final Faker faker;

    public JsonMigrationService(JsonMaskingConfigService configService) {
        this.configService = configService;
        this.objectMapper = new ObjectMapper();
        this.faker = new Faker();
    }

    public void processMigration() {
        JsonMigrationConfig config = configService.getConfig();
        if (config == null || config.getSourceDir() == null || config.getDestDir() == null) {
            throw new IllegalStateException("JSON Migration configuration is missing or incomplete.");
        }

        Path sourceDir = Paths.get(config.getSourceDir());
        Path destDir = Paths.get(config.getDestDir());

        if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
            throw new IllegalArgumentException("Source directory does not exist or is not a directory: " + sourceDir);
        }

        try {
            if (!Files.exists(destDir)) {
                Files.createDirectories(destDir);
            }

            try (Stream<Path> paths = Files.walk(sourceDir)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".json"))
                        .forEach(file -> processFile(file, sourceDir, destDir, config));
            }
        } catch (IOException e) {
            log.error("Failed during JSON migration process", e);
            throw new RuntimeException("JSON migration failed", e);
        }
    }

    private void processFile(Path sourceFile, Path sourceBaseDir, Path destBaseDir, JsonMigrationConfig config) {
        log.info("Processing file: {}", sourceFile);
        try {
            // Read JSON
            JsonNode rootNode = objectMapper.readTree(sourceFile.toFile());

            // Apply Masking
            maskNode(rootNode, "", config);

            // Determine target path
            Path relativePath = sourceBaseDir.relativize(sourceFile);
            Path destFile = destBaseDir.resolve(relativePath);

            // Create parent dirs if needed
            Files.createDirectories(destFile.getParent());

            // Write JSON
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(destFile.toFile(), rootNode);
            log.info("Successfully migrated to: {}", destFile);

        } catch (Exception e) {
            log.error("Error processing file {}", sourceFile, e);
        }
    }

    private void maskNode(JsonNode node, String currentPath, JsonMigrationConfig config) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            objectNode.fieldNames().forEachRemaining(fieldName -> {
                JsonNode childNode = objectNode.get(fieldName);
                String childPath = currentPath.isEmpty() ? fieldName : currentPath + "." + fieldName;

                if (childNode.isValueNode()) {
                    // Check if this path matches a rule
                    if (config.getMaskingColumns().contains(childPath)) {
                        objectNode.set(fieldName, new TextNode(generateFakeData(fieldName, childNode.asText())));
                    } else if (config.getPartialMaskingColumns().contains(childPath)) {
                        objectNode.set(fieldName, new TextNode(applyPartialMasking(childNode.asText())));
                    }
                } else {
                    // Recursively process nested objects/arrays
                    maskNode(childNode, childPath, config);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode arrayElement : node) {
                // For arrays, the path remains the same for elements for rule matching purposes
                maskNode(arrayElement, currentPath, config);
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
}
