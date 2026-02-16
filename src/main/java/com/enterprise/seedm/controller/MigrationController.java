package com.enterprise.seedm.controller;

import com.enterprise.seedm.service.DestinationTableDiscoveryService;
import com.enterprise.seedm.service.TableDiscoveryService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.sql.SQLException;
import java.util.ArrayList;
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
    @Autowired
    private DestinationTableDiscoveryService destinationTableDiscoveryService;
    @Autowired
    private JobExplorer jobExplorer;

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
        List<String> tables = destinationTableDiscoveryService.discoverDestinationTables();

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
     * Manually trigger migration job and redirect to dashboard
     */
    @PostMapping("/start")
    public void startMigration(HttpServletResponse response) throws IOException {
        try {
            log.info("Starting migration job manually...");

            JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("startTime", System.currentTimeMillis())
                    .toJobParameters();

            // Launch the job
            JobExecution execution = jobLauncher.run(migrationJob, jobParameters);

            // Redirect to the dashboard page with the execution ID
            response.sendRedirect("/index.html?executionId=" + execution.getId());

        } catch (Exception e) {
            log.error("Failed to start migration", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to start migration: " + e.getMessage());
        }
    }
    
    @GetMapping("/status/{executionId}")
    public Map<String, Object> getJobStatus(@PathVariable Long executionId) {
        JobExecution execution = jobExplorer.getJobExecution(executionId);
        if (execution == null) {
             Map<String, Object> response = new HashMap<>();
             response.put("status", "NOT_FOUND");
             return response;
        }
        return getJobStatusResponse(execution);
    }

    private Map<String, Object> getJobStatusResponse(JobExecution execution) {
        Map<String, Object> response = new HashMap<>();
        response.put("executionId", execution.getId());
        response.put("status", execution.getStatus().toString());
        response.put("startTime", execution.getStartTime());
        response.put("endTime", execution.getEndTime());
        response.put("exitStatus", execution.getExitStatus().getExitCode());

        // System Metrics
        Map<String, Object> systemMetrics = new HashMap<>();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

        systemMetrics.put("heapUsedMB", heapUsage.getUsed() / (1024 * 1024));
        systemMetrics.put("heapMaxMB", heapUsage.getMax() / (1024 * 1024));
        systemMetrics.put("systemLoadAverage", osBean.getSystemLoadAverage());
        systemMetrics.put("availableProcessors", osBean.getAvailableProcessors());
        
        response.put("systemMetrics", systemMetrics);

        // Table Progress
        List<Map<String, Object>> tableProgress = new ArrayList<>();
        List<String> completedTables = new ArrayList<>();
        
        for (StepExecution stepExecution : execution.getStepExecutions()) {
            Map<String, Object> stepInfo = new HashMap<>();
            String stepName = stepExecution.getStepName();
            
            // Only include table migration steps in the progress list
            if (stepName.startsWith("migrate_")) {
                String tableName = stepName.substring("migrate_".length());
                stepInfo.put("tableName", tableName);
                stepInfo.put("readCount", stepExecution.getReadCount());
                stepInfo.put("writeCount", stepExecution.getWriteCount());
                stepInfo.put("status", stepExecution.getStatus().toString());
                
                if (stepExecution.getStatus() == BatchStatus.COMPLETED) {
                    completedTables.add(tableName);
                }
                tableProgress.add(stepInfo);
            }
        }
        
        response.put("completedTables", completedTables);
        response.put("tableProgress", tableProgress);

        return response;
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
