package com.enterprise.seedm.controller;

import com.enterprise.seedm.config.SwappableDataSource;
import com.enterprise.seedm.service.DestinationTableDiscoveryService;
import com.enterprise.seedm.service.JsonMigrationService;
import com.enterprise.seedm.service.MigrationJobFactory;
import com.enterprise.seedm.service.MongoMigrationService;
import com.enterprise.seedm.service.TableDiscoveryService;
import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.service.JobApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    @Autowired
    private JobOperator jobOperator;
    @Qualifier("applicationTaskExecutor")
    @Autowired
    private TaskExecutor taskExecutor;
    private SecureRandom random;
    private JobExecution jobExecution=null;

    @Autowired
    private JsonMigrationService jsonMigrationService;

    @Autowired
    private MongoMigrationService mongoMigrationService;

    @Autowired
    private JobApprovalService jobApprovalService;

    @Qualifier("sourceDataSource")
    @Autowired
    private SwappableDataSource sourceDataSource;

    @Qualifier("destinationDataSource")
    @Autowired
    private SwappableDataSource destinationDataSource;

    /**
     * Get connection details from the active datasources
     */
    @GetMapping("/connections")
    public Map<String, String> getConnections() {
        Map<String, String> response = new HashMap<>();
        
        try {
            if (sourceDataSource.getTargetDataSource() != null) {
                response.put("sourceUrl", sourceDataSource.getConnection().getMetaData().getURL());
            } else {
                response.put("sourceUrl", "Not Connected");
            }
            
            if (destinationDataSource.getTargetDataSource() != null) {
                response.put("destinationUrl", destinationDataSource.getConnection().getMetaData().getURL());
            } else {
                response.put("destinationUrl", "Not Connected");
            }
        } catch (SQLException e) {
            log.error("Failed to get connection URLs", e);
            response.put("sourceUrl", "Error getting connection");
            response.put("destinationUrl", "Error getting connection");
        }
        
        return response;
    }

    /**
     * Preview the migration details (tables and row counts)
     */
    @GetMapping("/preview")
    public Map<String, Object> getMigrationPreview() {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> tablesInfo = new ArrayList<>();
        long totalRows = 0;
        int totalTables = 0;

        try {
            List<String> tables = tableDiscoveryService.discoverTables();
            totalTables = tables.size();

            for (String tableName : tables) {
                long rowCount = tableDiscoveryService.getTableRowCount(tableName);
                totalRows += rowCount;
                
                Map<String, Object> tableInfo = new HashMap<>();
                tableInfo.put("tableName", tableName);
                tableInfo.put("rowCount", rowCount);
                tablesInfo.add(tableInfo);
            }
        } catch (SQLException e) {
            log.error("Failed to get migration preview", e);
            response.put("error", e.getMessage());
        }

        response.put("tables", tablesInfo);
        response.put("totalTables", totalTables);
        response.put("totalRows", totalRows);
        return response;
    }

    @GetMapping("/preview/destination/{tableName}")
    public Map<String, Object> getDestinationTablePreview(@PathVariable String tableName) {
        Map<String, Object> response = new HashMap<>();
        long count = destinationTableDiscoveryService.getTableRowCount(tableName);
        
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
                } catch (JobRestartException e) {
                    throw new RuntimeException(e);
                } catch (JobParametersInvalidException e) {
                    throw new RuntimeException(e);
                }
            });
            // Try to find the most recent job if jobExecution is still null
            // This happens because jobLauncher.run is async and we need to redirect immediately
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
                response.sendRedirect("/index.html?executionId=" + jobExecution.getId());
            } else {
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
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Migration failed to start: " + e.getMessage());
        }
    }

    @PostMapping("/json/start")
    public ResponseEntity<?> startJsonMigration() {
        try {
            log.info("Starting JSON migration job manually...");
            
            // Execute the JSON migration process asynchronously
            String executionId = "json-" + System.currentTimeMillis();
            taskExecutor.execute(() -> {
                try {
                    jsonMigrationService.processMigrationAsync(executionId);
                } catch (Exception e) {
                    log.error("JSON Migration failed in background task", e);
                }
            });
            
            log.info("JSON Migration task launched with id: {}", executionId);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "executionId", executionId, "message", "JSON Migration started"));

        } catch (Exception e) {
            log.error("Failed to start JSON migration", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @GetMapping("/json/status/{executionId}")
    public ResponseEntity<?> getJsonStatus(@PathVariable String executionId) {
        return ResponseEntity.ok(jsonMigrationService.getProgress(executionId));
    }

    @PostMapping("/mongo/start/{id}")
    public ResponseEntity<?> startMongoMigration(@PathVariable Long id) {
        try {
            log.info("Starting Mongo migration job manually...");
            JobRequest jobRequest = jobApprovalService.getJob(id);
            if (jobRequest == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Job not found"));
            }

            // Execute the Mongo migration process asynchronously
            String executionId = "mongo-" + System.currentTimeMillis();
            taskExecutor.execute(() -> {
                try {
                    mongoMigrationService.migrate(jobRequest, executionId);
                } catch (Exception e) {
                    log.error("Mongo Migration failed in background task", e);
                }
            });

            log.info("Mongo Migration task launched with id: {}", executionId);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "executionId", executionId, "message", "Mongo Migration started"));

        } catch (Exception e) {
            log.error("Failed to start Mongo migration", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @GetMapping("/mongo/status/{executionId}")
    public ResponseEntity<?> getMongoStatus(@PathVariable String executionId) {
        return ResponseEntity.ok(mongoMigrationService.getProgress(executionId));
    }

    /**
     * Get a list of all recent job executions for the dropdown
     */
    @GetMapping("/executions")
    public List<Map<String, Object>> getRecentExecutions() {
        String jobName = "DBMigrationJob";
        List<Map<String, Object>> result = new ArrayList<>();
        
        try {
            // Get last few instances from Spring Batch
            List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 20);
            
            for (JobInstance instance : instances) {
                List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
                for (JobExecution execution : executions) {
                    Map<String, Object> execMap = new HashMap<>();
                    execMap.put("id", execution.getId().toString()); // Convert to String to match other types
                    execMap.put("status", execution.getStatus().toString());
                    execMap.put("startTime", execution.getStartTime() != null ? execution.getStartTime().getTime() : 0);
                    result.add(execMap);
                }
            }
            
            // Add JSON executions
            result.addAll(jsonMigrationService.getAllExecutions());
            
            // Add Mongo executions
            result.addAll(mongoMigrationService.getAllExecutions());
            
            // Sort descending by ID (using string comparison for mixed types)
            result.sort((m1, m2) -> {
                String id1 = m1.get("id").toString();
                String id2 = m2.get("id").toString();
                return id2.compareTo(id1);
            });
            
        } catch (Exception e) {
            log.error("Failed to fetch executions", e);
        }
        
        return result;
    }

    /**
     * Get real-time status of a specific migration job
     */
    @GetMapping("/status/{executionId}")
    public Map<String, Object> getJobStatus(@PathVariable String executionId) {
        if (executionId.startsWith("mongo-")) {
            return mongoMigrationService.getProgress(executionId);
        } else if (executionId.startsWith("json-")) {
            return jsonMigrationService.getProgress(executionId);
        }

        try {
            Long id = Long.parseLong(executionId);
            JobExecution execution = jobExplorer.getJobExecution(id);
            if (execution == null) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "NOT_FOUND");
                return response;
            }
            return getJobStatusResponse(execution);
        } catch (NumberFormatException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "INVALID_ID");
            return response;
        }
    }

    private Map<String, Object> getJobStatusResponse(JobExecution execution) {
        Map<String, Object> response = new HashMap<>();
        response.put("executionId", execution.getId().toString());
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
            
            // 2. Get step-level exceptions
            for (StepExecution stepExecution : execution.getStepExecutions()) {
                if (!stepExecution.getFailureExceptions().isEmpty()) {
                    stepExecution.getFailureExceptions().forEach(e -> {
                        errors.add("Step '" + stepExecution.getStepName() + "': " + e.getMessage());
                    });
                }
            }
            
            // 3. Get job-level exceptions (if any)
            if (!execution.getFailureExceptions().isEmpty()) {
                 execution.getFailureExceptions().forEach(e -> {
                     errors.add("Job Error: " + e.getMessage());
                 });
            }
            
            response.put("errors", errors);
        }

        // Calculate progress based on steps
        int totalSteps = 0;
        int completedSteps = 0;
        List<Map<String, Object>> tableProgress = new ArrayList<>();
        List<String> completedTables = new ArrayList<>();
        
        for (StepExecution stepExecution : execution.getStepExecutions()) {
            totalSteps++;
            if (stepExecution.getStatus() == BatchStatus.COMPLETED) {
                completedSteps++;
            }

            // Extract table-specific progress
            String stepName = stepExecution.getStepName();
            if (stepName.endsWith("-migration")) {
                String tableName = stepName.replace("-migration", "");
                Map<String, Object> stepInfo = new HashMap<>();
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
                 String exitDesc = stepExecution.getExitStatus().getExitDescription();
                 if (exitDesc != null && !exitDesc.isEmpty()) {
                     stepErrors.add(exitDesc);
                 }
                 
                 // Add explicit failure exceptions
                 stepExecution.getFailureExceptions().forEach(e -> stepErrors.add(e.getMessage()));
                 
                 response.put("stepErrors", Map.of(stepName, stepErrors));
            }
        }
        
        response.put("completedTables", completedTables);

        int progress = totalSteps == 0 ? 0 : (int) ((completedSteps / (double) totalSteps) * 100);
        response.put("progress", progress);
        response.put("tableProgress", tableProgress);

        return response;
    }

    /**
     * Stop a running migration job
     */
    @PostMapping("/stop/{executionId}")
    public Map<String, String> stopMigration(@PathVariable String executionId) {
        Map<String, String> response = new HashMap<>();
        
        if (executionId.startsWith("mongo-") || executionId.startsWith("json-")) {
            // Stopping async JSON/Mongo jobs dynamically might require extra tracking inside those services
            // Assuming no stop functionality defined for async in memory for now
            response.put("status", "ERROR");
            response.put("message", "Stopping is only supported for SQL Batch Jobs currently");
            return response;
        }

        try {
            Long id = Long.parseLong(executionId);
            JobExecution execution = jobExplorer.getJobExecution(id);
            if (execution != null && execution.isRunning()) {
                jobOperator.stop(id);
                response.put("status", "SUCCESS");
                response.put("message", "Job stop requested");
            } else {
                response.put("status", "ERROR");
                response.put("message", "Job is not running");
            }
        } catch (Exception e) {
            log.error("Failed to stop job", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
        }
        return response;
    }

    /**
     * Simple ping endpoint for health checks
     */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "PostgreSQL Migrator");
        return response;
    }
}
