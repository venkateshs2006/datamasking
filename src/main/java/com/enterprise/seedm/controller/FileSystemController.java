package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.service.DbConnectionService;
import com.enterprise.seedm.service.CosConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/fs")
@Slf4j
@RequiredArgsConstructor
public class FileSystemController {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DbConnectionService dbConnectionService;
    private final CosConnectionService cosConnectionService;

    @PostMapping("/scan")
    public ResponseEntity<?> scanDirectory(@RequestBody Map<String, Object> request) {
        String dirPath = (String) request.get("path");
        Object idObj = request.get("id");
        Object cosIdObj = request.get("cosId");
        
        Long id = null;
        if (idObj != null) {
            if (idObj instanceof Integer) id = ((Integer) idObj).longValue();
            else if (idObj instanceof Long) id = (Long) idObj;
            else if (idObj instanceof String) {
                try { id = Long.parseLong((String) idObj); } catch (Exception e) {}
            }
        }
        
        Long cosId = null;
        if (cosIdObj != null) {
            if (cosIdObj instanceof Integer) cosId = ((Integer) cosIdObj).longValue();
            else if (cosIdObj instanceof Long) cosId = (Long) cosIdObj;
            else if (cosIdObj instanceof String) {
                try { cosId = Long.parseLong((String) cosIdObj); } catch (Exception e) {}
            }
        }

        // Mock COS logic since actual IBM COS SDK is out of scope
        if (cosId != null) {
            CosConnection conn = cosConnectionService.getConnection(cosId);
            if (conn == null) return ResponseEntity.badRequest().body(Map.of("error", "COS Connection not found"));
            
            // Mocking sample keys that would be found in a COS bucket JSON file
            Set<String> mockedKeys = Set.of("customer.id", "customer.name", "customer.email", "customer.phone", "customer.address.city", "transaction.amount", "transaction.date");
            
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "path", "cos://" + conn.getBucketName(),
                    "fileCount", 5, // Mock 5 files found in bucket
                    "sampleKeys", new ArrayList<>(mockedKeys)
            ));
        }

        if (id != null) {
            DbConnection conn = dbConnectionService.getConnection(id);
            if (conn != null) {
                if ("json".equalsIgnoreCase(conn.getDbType())) {
                    dirPath = conn.getUrl();
                } else {
                    return ResponseEntity.badRequest().body(Map.of("error", "Connection type is not json. Cannot scan directory for a database connection."));
                }
            }
        }

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
}
