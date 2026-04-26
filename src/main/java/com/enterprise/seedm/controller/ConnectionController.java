package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.model.DbConnectionRequest;
import com.enterprise.seedm.service.DbConnectionService;
import com.enterprise.seedm.service.DynamicDataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connection")
@RequiredArgsConstructor
@Slf4j
public class ConnectionController {

    private final DynamicDataSourceService dynamicDataSourceService;
    private final DbConnectionService connectionService;

    private void resolveConnection(DbConnectionRequest request) {
        if (request.getId() != null) {
            DbConnection saved = connectionService.getConnection(request.getId());
            if (saved != null) {
                request.setUrl(saved.getUrl());
                request.setUsername(saved.getUsername());
                request.setPassword(saved.getPassword());
            } else {
                throw new IllegalArgumentException("Connection ID not found: " + request.getId());
            }
        }
    }

    @PostMapping("/schemas")
    public List<String> getSchemas(@RequestBody DbConnectionRequest request) {
        resolveConnection(request);
        log.info("Fetching schemas for {} connection", request.getType());
        return dynamicDataSourceService.fetchSchemas(request);
    }

    @PostMapping("/create-schema")
    public ResponseEntity<?> createSchema(@RequestBody DbConnectionRequest request) {
        try {
            resolveConnection(request);
            dynamicDataSourceService.createSchema(request);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Schema '" + request.getSchema() + "' created successfully."));
        } catch (Exception e) {
            log.error("Failed to create schema", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/update")
    public Map<String, String> updateConnection(@RequestBody DbConnectionRequest request) {
        resolveConnection(request);
        log.info("Updating {} connection", request.getType());
        dynamicDataSourceService.updateConnection(request);
        return Map.of("status", "SUCCESS", "message", request.getType() + " connection updated successfully");
    }
}
