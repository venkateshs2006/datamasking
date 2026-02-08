package com.enterprise.seedm.controller;

import com.enterprise.seedm.service.TableDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Migration Controller
 * REST API to trigger and monitor migrations
 */
@RestController
@RequestMapping("/api/migration")
@RequiredArgsConstructor
@Slf4j
public class MigrationController {
    @Autowired
    private  JobLauncher jobLauncher;
    @Autowired
    private  Job migrationJob;
    @Autowired
    private TableDiscoveryService tableDiscoveryService;

    /**
     * Get list of tables to be migrated
     */
    @GetMapping("/tables")
    public Map<String, Object> getTables() throws SQLException {
        List<String> tables = tableDiscoveryService.discoverTables();

        Map<String, Object> response = new HashMap<>();
        response.put("tableCount", tables.size());
        response.put("tables", tables);

        return response;
    }
    @GetMapping("/destination/tables")
    public Map<String, Object> getDestinationTables() throws SQLException {
        List<String> tables = tableDiscoveryService.discoverDestinationTables();

        Map<String, Object> response = new HashMap<>();
        response.put("tableCount", tables.size());
        response.put("tables", tables);

        return response;
    }
    /**
     * Get row count for a specific table
     */
    @GetMapping("/tables/{tableName}/count")
    public Map<String, Object> getTableCount(@PathVariable String tableName) {
        long count = tableDiscoveryService.getTableRowCount(tableName);

        Map<String, Object> response = new HashMap<>();
        response.put("tableName", tableName);
        response.put("rowCount", count);

        return response;
    }

    /**
     * Manually trigger migration job
     */
    @PostMapping("/start")
    public Map<String, Object> startMigration() {
        try {
            log.info("Starting migration job manually...");

            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("startTime", System.currentTimeMillis())
                    .toJobParameters();

            var execution = jobLauncher.run(migrationJob, jobParameters);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "STARTED");
            response.put("executionId", execution.getId());
            response.put("startTime", execution.getStartTime());

            return response;

        } catch (Exception e) {
            log.error("Failed to start migration", e);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "FAILED");
            response.put("error", e.getMessage());

            return response;
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "PostgreSQL Migrator");
        return response;
    }
}