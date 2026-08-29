package com.enterprise.seedm.controller;

import com.enterprise.seedm.service.SecureImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/secure-import")
@RequiredArgsConstructor
@Slf4j
public class SecureImportController {

    private final SecureImportService secureImportService;

    @PostMapping("/scan")
    public ResponseEntity<?> scanStorage(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(secureImportService.scanStorage(request));
    }

    @GetMapping("/status/{executionId}")
    public ResponseEntity<?> getStatus(@PathVariable String executionId) {
        return ResponseEntity.ok(secureImportService.getProgress(executionId));
    }

    @GetMapping("/executions")
    public ResponseEntity<?> getAllExecutions() {
        return ResponseEntity.ok(secureImportService.getAllExecutions());
    }
}
