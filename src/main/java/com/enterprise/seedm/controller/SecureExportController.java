package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.SecureExportConfig;
import com.enterprise.seedm.service.JsonMigrationService;
import com.enterprise.seedm.service.SecureExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/secure-export")
public class SecureExportController {

    private final JsonMigrationService jsonMigrationService;
    private final SecureExportService secureExportService;

    public SecureExportController(JsonMigrationService jsonMigrationService, SecureExportService secureExportService) {
        this.jsonMigrationService = jsonMigrationService;
        this.secureExportService = secureExportService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startSecureExport(@RequestBody SecureExportConfig config) {
        String executionId = jsonMigrationService.runSecureExportAsync(config);
        return ResponseEntity.ok(Map.of("executionId", executionId));
    }

    @GetMapping("/progress/{executionId}")
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable String executionId) {
        return ResponseEntity.ok(secureExportService.getProgress(executionId));
    }

    @GetMapping("/executions")
public ResponseEntity<List<Map<String, Object>>> getAllExecutions() {
        return ResponseEntity.ok(secureExportService.getAllExecutions());
    }
}