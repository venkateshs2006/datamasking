package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.JsonSecureExportConfig;
import com.enterprise.seedm.service.JsonSecureExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/json-secure-export")
@RequiredArgsConstructor
@Slf4j
public class JsonSecureExportController {

    private final JsonSecureExportService jsonSecureExportService;
    private final TaskExecutor taskExecutor;
    private final AtomicInteger executionSequence = new AtomicInteger(1);

    @PostMapping("/scan")
    public ResponseEntity<?> scanSourceFiles(@RequestBody JsonSecureExportConfig.StorageConfig source) {
        Map<String, Object> result = jsonSecureExportService.scanSourceFiles(source);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/fields")
    public ResponseEntity<?> sampleJsonFields(@RequestBody Map<String, Object> payload) {
        try {
            JsonSecureExportConfig.StorageConfig source = new JsonSecureExportConfig.StorageConfig();
            if (payload.containsKey("source")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> srcMap = (Map<String, Object>) payload.get("source");
                source.setType((String) srcMap.get("type"));
                if (srcMap.get("cosId") != null) source.setCosId(Long.valueOf(srcMap.get("cosId").toString()));
                source.setPath((String) srcMap.get("path"));
            }
            String fileName = (String) payload.get("fileName");
            Map<String, Object> result = jsonSecureExportService.sampleJsonFields(source, fileName);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to sample fields", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> startExport(@RequestBody JsonSecureExportConfig config) {
        try {
            String executionId = "json-export-" + executionSequence.getAndIncrement();
            taskExecutor.execute(() -> {
                try {
                    jsonSecureExportService.processJsonExport(executionId, config);
                } catch (Exception e) {
                    log.error("JSON secure export failed in async thread", e);
                }
            });

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "executionId", executionId,
                    "message", "JSON Secure Export started"
            ));
        } catch (Exception e) {
            log.error("Failed to start JSON secure export", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/status/{executionId}")
    public ResponseEntity<?> getStatus(@PathVariable String executionId) {
        Map<String, Object> progress = jsonSecureExportService.getProgress(executionId);
        return ResponseEntity.ok(progress);
    }
}
