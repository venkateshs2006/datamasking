package com.enterprise.seedm.controller;

import com.enterprise.seedm.config.SwappableDataSource;
import com.enterprise.seedm.service.DestinationTableDiscoveryService;
import com.enterprise.seedm.service.JsonMigrationService;
import com.enterprise.seedm.service.MigrationJobFactory;
import com.enterprise.seedm.service.TableDiscoveryService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private MigrationJobFactory migrationJobFactory;

    @Autowired
    private TableDiscoveryService tableDiscoveryService;
    @Autowired
    private DestinationTableDiscoveryService destinationTableDiscoveryService;
    @Autowired
    private JobExplorer jobExplorer;
    @Qualifier("applicationTaskExecutor")
    @Autowired
    private TaskExecutor taskExecutor;
    private SecureRandom random;
    private JobExecution jobExecution=null;

    @Autowired
    private JsonMigrationService jsonMigrationService;

    @Qualifier("sourceDataSource")
    @Autowired
    private SwappableDataSource sourceDataSource;

    @Qualifier("destinationDataSource")
    @Autowired
    private SwappableDataSource destinationDataSource;

    /**
     * Get connection details from the active datasources
     */
    @GetMapping("/connection-details")
    public Map<String, String> getConnectionDetails() {
        Map<String, String> details = new HashMap<>();
        
        details.put("sourceUrl", getUrlFromDataSource(sourceDataSource));
        details.put("destinationUrl", getUrlFromDataSource(destinationDataSource));
        
        return details;
    }

    private String getUrlFromDataSource(SwappableDataSource swappableDataSource) {
        DataSource target = swappableDataSource.getTargetDataSource();
        if (target instanceof HikariDataSource) {
            return ((HikariDataSource) target).getJdbcUrl();
        }
        return "Unknown DataSource Type";
    }

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
            random = new SecureRandom();
            log.info("Starting migration job manually...");
            
            // Create a fresh job instance based on current DB connection/schema
            Job migrationJob = migrationJobFactory.createMigrationJob();
            
            int uniqueId=random.nextInt();
            String jobName="DBMigrationJob";
            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("jobName",jobName)
                    .addString("name",jobName)
                    .addLong("startTime", System.currentTimeMillis())
                    .toJobParameters();
            taskExecutor.execute(()->{
                try {
                    jobExecution = jobLauncher.run(migrationJob, jobParameters);
                } catch (JobInstanceAlreadyCompleteException e) {
                    throw new RuntimeException(e);
                } catch (JobExecutionAlreadyRunningException e) {
                    throw new RuntimeException(e);
                } catch (JobParametersInvalidException e) {
                    throw new RuntimeException(e);
                } catch (JobRestartException e) {
                    throw new RuntimeException(e);
                }
            });
            // We need to get the execution ID *before* we return, but the job runs async.
            // Since we configured the JobLauncher to be async in BatchConfig, jobLauncher.run() returns immediately
            // with the JobExecution that has been persisted to the DB (status STARTING/STARTED).
            if(jobExecution==null){
                // Wait briefly for the async thread to start the job
                Thread.sleep(500); 
                if (jobExecution == null) {
                     // Fallback: try to find it, get the latest one
                     jobExecution = jobExplorer.findRunningJobExecutions(jobName).stream()
                             .max(Comparator.comparing(JobExecution::getId))
                             .orElse(null);
                }
            }
            
            if (jobExecution != null) {
                log.info("Job launched with Execution ID: {}", jobExecution.getId());
                // Redirect to the dashboard page with the execution ID immediately
                response.sendRedirect("/index.html?executionId=" + jobExecution.getId());
            } else {
                // If we still can't find it, maybe it finished very quickly or failed to start.
                // Try to find the most recent execution regardless of status
                JobInstance lastInstance = jobExplorer.getLastJobInstance(jobName);
                if (lastInstance != null) {
                    jobExecution = jobExplorer.getLastJobExecution(lastInstance);
                    if (jobExecution != null) {
                        log.info("Found recent job execution ID: {}", (jobExecution.getId()));
                        response.sendRedirect("/index.html?executionId=" + (jobExecution.getId()));
                        return;
                    }
                }
                throw new RuntimeException("Failed to obtain JobExecution ID. Job might not have started yet.");
            }

        } catch (Exception e) {
            log.error("Failed to start migration", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to start migration: " + e.getMessage());
        }
    }

    @PostMapping("/json/start")
    public void startJsonMigration(HttpServletResponse response) throws IOException {
        try {
            log.info("Starting JSON migration job manually...");
            
            // Execute the JSON migration process synchronously for now, or could be wrapped in TaskExecutor
            taskExecutor.execute(() -> {
                try {
                    jsonMigrationService.processMigration();
                } catch (Exception e) {
                    log.error("JSON Migration failed in background task", e);
                }
            });
            
            log.info("JSON Migration task launched");
            // Redirect to a specific page or back to home. Since we don't have a Spring Batch Job ID for this custom process,
            // we'll redirect back to the DB select page or a success page.
            response.sendRedirect("/select-db.html?msg=json_started");

        } catch (Exception e) {
            log.error("Failed to start JSON migration", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to start JSON migration: " + e.getMessage());
        }
    }

    /**
     * Get a list of all recent job executions for the dropdown
     */
    @GetMapping("/executions")
    public List<Map<String, Object>> getRecentExecutions() {
        String jobName = "DBMigrationJob";
        List<Map<String, Object>> result = new ArrayList<>();
        
        try {
            // Get last few instances
            List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 20);
            
            for (JobInstance instance : instances) {
                List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
                for (JobExecution execution : executions) {
                    Map<String, Object> execMap = new HashMap<>();
                    execMap.put("id", execution.getId());
                    execMap.put("status", execution.getStatus().toString());
                    execMap.put("startTime", execution.getStartTime());
                    result.add(execMap);
                }
            }
            
            // Sort descending by ID
            result.sort((m1, m2) -> ((Long) m2.get("id")).compareTo((Long) m1.get("id")));
            
        } catch (Exception e) {
            log.error("Failed to fetch executions", e);
        }
        
        return result;
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
        
        // Add failure exceptions if any
        if (execution.getStatus() == BatchStatus.FAILED) {
            List<String> errors = new ArrayList<>();
            
            // 1. Get Exit Description (often contains the main error message)
            String exitDescription = execution.getExitStatus().getExitDescription();
            if (exitDescription != null && !exitDescription.isEmpty()) {
                errors.add(exitDescription);
            }
            
            // 2. Get Failure Exceptions
            List<String> exceptionErrors = execution.getAllFailureExceptions().stream()
                    .map(this::formatExceptionMessage)
                    .collect(Collectors.toList());
            errors.addAll(exceptionErrors);
            
            response.put("errors", errors);
        }

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
            } else if (stepExecution.getStatus() == BatchStatus.FAILED) {
                 // Capture constraint creation failure specifically
                 List<String> stepErrors = new ArrayList<>();
                 
                 // Add exit description for the step
                 String stepExitDesc = stepExecution.getExitStatus().getExitDescription();
                 if (stepExitDesc != null && !stepExitDesc.isEmpty()) {
                     stepErrors.add(stepExitDesc);
                 }

                 // Add exceptions
                 stepErrors.addAll(stepExecution.getFailureExceptions().stream()
                         .map(this::formatExceptionMessage)
                         .collect(Collectors.toList()));
                         
                 response.put("constraintErrors", stepErrors);
            }
        }

        response.put("completedTables", completedTables);
        response.put("tableProgress", tableProgress);

        return response;
    }
    
    private String formatExceptionMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getMessage());
        
        // Traverse the cause chain to get all nested causes
        Throwable cause = t.getCause();
        while (cause != null) {
            sb.append(" | Caused by: ").append(cause.getMessage());
            cause = cause.getCause();
        }
        return sb.toString();
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
