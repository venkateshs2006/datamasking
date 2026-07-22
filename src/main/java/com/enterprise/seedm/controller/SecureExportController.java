package com.enterprise.seedm.controller;

import com.enterprise.seedm.dto.SecureExportRequest;
import com.enterprise.seedm.service.SecureExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/secure-export")
public class SecureExportController {

    @Autowired
    private SecureExportService secureExportService;

    @PostMapping("/create")
    public ResponseEntity<?> createSecureExportJob(@RequestBody SecureExportRequest request) {
        try {
            secureExportService.createJob(request);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
