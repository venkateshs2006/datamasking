package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.CsvSecureImportConfig;
import com.enterprise.seedm.service.CsvSecureImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping({"/api/csv-secure-import", "/api/csv-import"})
@RequiredArgsConstructor
@Slf4j
public class CsvSecureImportController {

    private final CsvSecureImportService csvSecureImportService;
    private final TaskExecutor taskExecutor;
    private final AtomicInteger executionSequence = new AtomicInteger(1);

    @PostMapping("/scan")
    public ResponseEntity<?> scanStorage(@RequestBody CsvSecureImportConfig.StorageConfig storage) {
        Map<String, Object> result = csvSecureImportService.scanStorage(storage);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/start")
    public ResponseEntity<?> startImport(@RequestBody CsvSecureImportConfig config) {
        try {
            String secretKey = config.getSecretKey();
            // Validate key
            csvSecureImportService.validateSecretKey(config, secretKey);

            String executionId = "csv-import-" + executionSequence.getAndIncrement();
            taskExecutor.execute(() -> {
                try {
                    csvSecureImportService.processCsvImport(executionId, config, secretKey);
                } catch (Exception e) {
                    log.error("CSV secure import failed in async thread", e);
                }
            });

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "executionId", executionId,
                    "message", "CSV Secure Import started"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start CSV secure import", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @GetMapping("/status/{executionId}")
    public ResponseEntity<?> getStatus(@PathVariable String executionId) {
        Map<String, Object> progress = csvSecureImportService.getProgress(executionId);
        return ResponseEntity.ok(progress);
    }
}
