package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.SecureExportConfig;
import com.enterprise.seedm.service.SecureExportService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/secure-export")
public class SecureExportController {

    private final SecureExportService secureExportService;
    private final TaskExecutor taskExecutor;

    public SecureExportController(SecureExportService secureExportService, @Qualifier("secureExportTaskExecutor") TaskExecutor taskExecutor) {
        this.secureExportService = secureExportService;
        this.taskExecutor = taskExecutor;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startSecureExport(@RequestBody SecureExportConfig config) {
        String executionId = UUID.randomUUID().toString();
        taskExecutor.execute(() -> secureExportService.processSecureExport(executionId, config));
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