package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.SecureExportConfig;
import com.enterprise.seedm.service.SecureExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/secure-export")
@RequiredArgsConstructor
@Slf4j
public class SecureExportController {

    @Autowired
    private SecureExportService secureExportService;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private TaskExecutor taskExecutor;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping("/start")
    public ResponseEntity<?> startSecureExport(@RequestBody SecureExportConfig config) {
        try {
            String executionId = "secure-export-" + UUID.randomUUID().toString();
            taskExecutor.execute(() -> {
                try {
                    secureExportService.processSecureExport(executionId, config);
                } catch (Exception e) {
                    log.error("Secure Export failed in background task", e);
                }
            });
            log.info("Secure Export task launched with id: {}", executionId);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "executionId", executionId, "message", "Secure Export started"));
        } catch (Exception e) {
            log.error("Failed to start Secure Export", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @GetMapping("/status/{executionId}")
    public ResponseEntity<?> getStatus(@PathVariable String executionId) {
        return ResponseEntity.ok(secureExportService.getProgress(executionId));
    }

    @GetMapping("/executions")
    public ResponseEntity<List<Map<String, Object>>> getAllExecutions() {
        return ResponseEntity.ok(secureExportService.getAllExecutions());
    }
}