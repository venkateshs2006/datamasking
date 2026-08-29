package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.MongoSecureExportConfig;
import com.enterprise.seedm.service.MongoSecureExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/mongo-secure-export")
@RequiredArgsConstructor
@Slf4j
public class MongoSecureExportController {

    private final MongoSecureExportService mongoSecureExportService;
    private final TaskExecutor taskExecutor;
    private final AtomicInteger executionSequence = new AtomicInteger(1);

    @PostMapping("/start")
    public ResponseEntity<?> startExport(@RequestBody MongoSecureExportConfig config) {
        try {
            String executionId = "mongo-export-" + executionSequence.getAndIncrement();
            taskExecutor.execute(() -> {
                try {
                    mongoSecureExportService.processMongoExport(executionId, config);
                } catch (Exception e) {
                    log.error("Mongo secure export failed in async thread", e);
                }
            });

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "executionId", executionId,
                    "message", "MongoDB Secure Export started"
            ));
        } catch (Exception e) {
            log.error("Failed to start Mongo secure export", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/status/{executionId}")
    public ResponseEntity<?> getStatus(@PathVariable String executionId) {
        Map<String, Object> progress = mongoSecureExportService.getProgress(executionId);
        return ResponseEntity.ok(progress);
    }
}
