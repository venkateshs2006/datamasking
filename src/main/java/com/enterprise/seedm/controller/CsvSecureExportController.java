package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.CsvSecureExportConfig;
import com.enterprise.seedm.service.CsvSecureExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping({"/api/csv-secure-export", "/api/csv-export"})
@RequiredArgsConstructor
@Slf4j
public class CsvSecureExportController {

    private final CsvSecureExportService csvSecureExportService;
    private final TaskExecutor taskExecutor;
    private final AtomicInteger executionSequence = new AtomicInteger(1);

    @PostMapping("/scan")
    public ResponseEntity<?> scanSourceFiles(@RequestBody CsvSecureExportConfig.StorageConfig source) {
        Map<String, Object> result = csvSecureExportService.scanSourceFiles(source);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/fields")
    public ResponseEntity<?> sampleCsvFields(@RequestBody Map<String, Object> payload) {
        try {
            CsvSecureExportConfig.StorageConfig source = new CsvSecureExportConfig.StorageConfig();
            if (payload.containsKey("source")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> srcMap = (Map<String, Object>) payload.get("source");
                source.setType((String) srcMap.get("type"));
                if (srcMap.get("cosId") != null) source.setCosId(Long.valueOf(srcMap.get("cosId").toString()));
                else if (srcMap.get("id") != null) source.setCosId(Long.valueOf(srcMap.get("id").toString()));
                source.setPath((String) srcMap.get("path"));
            }
            String fileName = (String) payload.get("fileName");
            Map<String, Object> result = csvSecureExportService.sampleCsvHeaders(source, fileName);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to sample CSV fields", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @GetMapping("/fields")
    public ResponseEntity<?> sampleCsvFieldsGet(@RequestParam(required = false, defaultValue = "local") String type,
                                               @RequestParam(required = false) Long cosId,
                                               @RequestParam(required = false) String path,
                                               @RequestParam String fileName) {
        try {
            CsvSecureExportConfig.StorageConfig source = new CsvSecureExportConfig.StorageConfig();
            source.setType(type);
            source.setCosId(cosId);
            source.setPath(path);
            Map<String, Object> result = csvSecureExportService.sampleCsvHeaders(source, fileName);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to sample CSV fields", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> startExport(@RequestBody CsvSecureExportConfig config) {
        try {
            String executionId = "csv-export-" + executionSequence.getAndIncrement();
            taskExecutor.execute(() -> {
                try {
                    csvSecureExportService.processCsvExport(executionId, config);
                } catch (Exception e) {
                    log.error("CSV secure export failed in async thread", e);
                }
            });

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "executionId", executionId,
                    "message", "CSV Secure Export started"
            ));
        } catch (Exception e) {
            log.error("Failed to start CSV secure export", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/status/{executionId}")
    public ResponseEntity<?> getStatus(@PathVariable String executionId) {
        Map<String, Object> progress = csvSecureExportService.getProgress(executionId);
        return ResponseEntity.ok(progress);
    }
}
