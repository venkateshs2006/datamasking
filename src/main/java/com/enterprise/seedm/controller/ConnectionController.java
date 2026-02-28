package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.DbConnectionRequest;
import com.enterprise.seedm.service.DynamicDataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connection")
@RequiredArgsConstructor
@Slf4j
public class ConnectionController {

    private final DynamicDataSourceService dynamicDataSourceService;

    @PostMapping("/schemas")
    public List<String> getSchemas(@RequestBody DbConnectionRequest request) {
        log.info("Fetching schemas for {} connection", request.getType());
        return dynamicDataSourceService.fetchSchemas(request);
    }

    @PostMapping("/update")
    public Map<String, String> updateConnection(@RequestBody DbConnectionRequest request) {
        log.info("Updating {} connection", request.getType());
        dynamicDataSourceService.updateConnection(request);
        return Map.of("status", "SUCCESS", "message", request.getType() + " connection updated successfully");
    }
}
