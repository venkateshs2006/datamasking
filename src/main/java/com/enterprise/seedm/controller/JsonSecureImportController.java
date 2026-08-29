package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.JsonSecureImportConfig;
import com.enterprise.seedm.service.JsonSecureImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/json-secure-import")
@RequiredArgsConstructor
@Slf4j
public class JsonSecureImportController {

    private final JsonSecureImportService jsonSecureImportService;
    private final TaskExecutor taskExecutor;
    private final AtomicInteger executionSequence = new AtomicInteger(1);

    @PostMapping("/scan")
    public ResponseEntity<?> scanStorage(@RequestBody JsonSecureImportConfig.StorageConfig storage) {
        Map<String, Object> result = jsonSecureImportService.scanStorage(storage);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/start")
    public ResponseEntity<?> startImport(@RequestBody JsonSecureImportConfig config) {
        try {
            String secretKey = config.getSecretKey();
            // Validate key
            jsonSecureImportService.validateSecretKey(config, secretKey);

            String executionId = "json-import-" + executionSequence.getAndIncrement();
            taskExecutor.execute(() -> {
                try {
                    jsonSecureImportService.processJsonImport(executionId, config, secretKey);
                } catch (Exception e) {
                    log.error("JSON secure import failed in async thread", e);
                }
            });

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "executionId", executionId,
                    "message", "JSON Secure Import started"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start JSON secure import", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @GetMapping("/status/{executionId}")
    public ResponseEntity<?> getStatus(@PathVariable String executionId) {
        Map<String, Object> progress = jsonSecureImportService.getProgress(executionId);
        return ResponseEntity.ok(progress);
    }
}
