package com.enterprise.seedm.controller;

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
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/fs")
@Slf4j
public class FileSystemController {

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
            try (Stream<Path> walk = Files.walk(path)) {
                jsonFileCount = walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".json"))
                        .count();
            }

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "path", dirPath,
                    "fileCount", jsonFileCount
            ));

        } catch (Exception e) {
            log.error("Error scanning directory: {}", dirPath, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to scan directory: " + e.getMessage()));
        }
    }
}
