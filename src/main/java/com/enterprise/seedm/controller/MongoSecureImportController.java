package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.MongoSecureImportConfig;
import com.enterprise.seedm.service.MongoSecureImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/mongo-secure-import")
@RequiredArgsConstructor
@Slf4j
public class MongoSecureImportController {

    private final MongoSecureImportService mongoSecureImportService;
    private final TaskExecutor taskExecutor;
    private final AtomicInteger executionSequence = new AtomicInteger(1);

    @PostMapping("/scan")
    public ResponseEntity<?> scanStorage(@RequestBody MongoSecureImportConfig.StorageConfig storage) {
        Map<String, Object> result = mongoSecureImportService.scanStorage(storage);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/start")
    public ResponseEntity<?> startImport(@RequestBody MongoSecureImportConfig config) {
        try {
            String secretKey = config.getSecretKey();
            // Validate key
            mongoSecureImportService.validateSecretKey(config, secretKey);

            String executionId = "mongo-import-" + executionSequence.getAndIncrement();
            taskExecutor.execute(() -> {
                try {
                    mongoSecureImportService.processMongoImport(executionId, config, secretKey);
                } catch (Exception e) {
                    log.error("Mongo secure import failed in async thread", e);
                }
            });

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "executionId", executionId,
                    "message", "MongoDB Secure Import started"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start Mongo secure import", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @GetMapping("/status/{executionId}")
    public ResponseEntity<?> getStatus(@PathVariable String executionId) {
        Map<String, Object> progress = mongoSecureImportService.getProgress(executionId);
        return ResponseEntity.ok(progress);
    }
}
