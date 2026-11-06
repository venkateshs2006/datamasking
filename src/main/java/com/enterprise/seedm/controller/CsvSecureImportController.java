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

    @PostMapping("/inspect")
    public ResponseEntity<?> inspectBundle(@RequestBody Map<String, Object> payload) {
        try {
            CsvSecureImportConfig.StorageConfig storage = extractStorageConfig(payload);
            String secretKey = (String) payload.get("secretKey");
            Map<String, Object> result = csvSecureImportService.inspectSource(storage, secretKey);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to inspect CSV bundle", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/fields")
    public ResponseEntity<?> sampleCsvFields(@RequestBody Map<String, Object> payload) {
        try {
            CsvSecureImportConfig.StorageConfig storage = extractStorageConfig(payload);
            String fileName = (String) payload.get("fileName");
            String secretKey = (String) payload.get("secretKey");
            Map<String, Object> result = csvSecureImportService.sampleCsvHeaders(storage, fileName, secretKey);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to sample CSV import fields", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @GetMapping("/fields")
    public ResponseEntity<?> sampleCsvFieldsGet(@RequestParam(required = false, defaultValue = "local") String type,
                                               @RequestParam(required = false) Long cosId,
                                               @RequestParam(required = false) String path,
                                               @RequestParam(required = false) String fileName,
                                               @RequestParam(required = false) String secretKey) {
        try {
            CsvSecureImportConfig.StorageConfig storage = new CsvSecureImportConfig.StorageConfig();
            storage.setType(type);
            storage.setCosId(cosId);
            storage.setPath(path);
            storage.setFileName(fileName);
            Map<String, Object> result = csvSecureImportService.sampleCsvHeaders(storage, fileName, secretKey);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to sample CSV import fields", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    private CsvSecureImportConfig.StorageConfig extractStorageConfig(Map<String, Object> payload) {
        CsvSecureImportConfig.StorageConfig storage = new CsvSecureImportConfig.StorageConfig();
        if (payload.containsKey("storage") && payload.get("storage") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stMap = (Map<String, Object>) payload.get("storage");
            storage.setType((String) stMap.get("type"));
            if (stMap.get("cosId") != null) storage.setCosId(Long.valueOf(stMap.get("cosId").toString()));
            else if (stMap.get("id") != null) storage.setCosId(Long.valueOf(stMap.get("id").toString()));
            storage.setPath((String) stMap.get("path"));
            if (stMap.get("fileName") != null) storage.setFileName((String) stMap.get("fileName"));
        } else {
            if (payload.get("type") != null) storage.setType(payload.get("type").toString());
            if (payload.get("cosId") != null) storage.setCosId(Long.valueOf(payload.get("cosId").toString()));
            else if (payload.get("id") != null) storage.setCosId(Long.valueOf(payload.get("id").toString()));
            if (payload.get("path") != null) storage.setPath(payload.get("path").toString());
            if (payload.get("package") != null) storage.setFileName(payload.get("package").toString());
            else if (payload.get("fileName") != null) storage.setFileName(payload.get("fileName").toString());
        }
        return storage;
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
