package com.enterprise.seedm.controller;

import com.enterprise.seedm.config.SwappableDataSource;
import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.model.DbConnectionRequest;
import com.enterprise.seedm.model.JsonMigrationConfig;
import com.enterprise.seedm.model.SecureExportConfig;
import com.enterprise.seedm.model.SecureImportConfig;
import com.enterprise.seedm.service.*;
import com.enterprise.seedm.model.JobRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

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
    private SecureExportService secureExportService;

    @Autowired
    private SecureImportService secureImportService;

    @Autowired
    private JobApprovalService jobApprovalService;

    @Autowired
    private DynamicDataSourceService dynamicDataSourceService;

    @Autowired
    private DbConnectionService dbConnectionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Qualifier("sourceDataSource")
    @Autowired
    private SwappableDataSource sourceDataSource;

    @Qualifier("destinationDataSource")
    @Autowired
    private SwappableDataSource destinationDataSource;

    private static final AtomicLong jsonSequence = new AtomicLong(1);
    private static final AtomicLong mongoSequence = new AtomicLong(1);
    private static final AtomicLong secureExportSequence = new AtomicLong(1);
    private static final AtomicLong secureImportSequence = new AtomicLong(1);


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
    @PostMapping("/start/{id}")
    public void startMigration(@PathVariable Long id, HttpServletResponse response) throws IOException {
        try {
            random = new SecureRandom();
            log.info("Starting migration job manually...");

            JobRequest jobRequest = jobApprovalService.getJob(id);
            if (jobRequest == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Job not found");
                return;
            }

            Map<String, Object> configDetailsMap = objectMapper.convertValue(jobRequest.getConfigDetails(), Map.class);

            if (jobRequest.getJobType().equalsIgnoreCase("SECURE_EXPORT") || jobRequest.getJobType().equalsIgnoreCase("secure-export") || jobRequest.getJobType().equalsIgnoreCase("secure_export")) {
                SecureExportConfig config = new SecureExportConfig();
                config.setJobName(jobRequest.getMigrationName());

                if (configDetailsMap.containsKey("source")) {
                    Map<String, Object> sourceMap = (Map<String, Object>) configDetailsMap.get("source");
                    if (sourceMap.containsKey("id") && sourceMap.get("id") != null) {
                        DbConnection sourceConnection = dbConnectionService.getConnection(Long.valueOf(sourceMap.get("id").toString()));
                        if (sourceConnection != null) {
                            SecureExportConfig.SourceConfig srcConfig = new SecureExportConfig.SourceConfig();
                            srcConfig.setUrl(sourceConnection.getUrl());
                            srcConfig.setUsername(sourceConnection.getUsername());
                            srcConfig.setPassword(sourceConnection.getPassword());
                            if (sourceMap.containsKey("schema") && sourceMap.get("schema") != null) {
                                srcConfig.setSchema(sourceMap.get("schema").toString());
                            }
                            config.setSource(srcConfig);
                        }
                    }
                }

                if (config.getSource() == null && configDetailsMap.containsKey("source")) {
                    SecureExportConfig.SourceConfig srcConfig = objectMapper.convertValue(configDetailsMap.get("source"), SecureExportConfig.SourceConfig.class);
                    config.setSource(srcConfig);
                }

                String destDir = null;
                if (configDetailsMap.containsKey("storage")) {
                    Map<String, Object> storageMap = (Map<String, Object>) configDetailsMap.get("storage");
                    if (storageMap.get("path") != null) {
                        destDir = storageMap.get("path").toString();
                    }
                }
                if (destDir == null && configDetailsMap.containsKey("dest")) {
                    Map<String, Object> destMap = (Map<String, Object>) configDetailsMap.get("dest");
                    if (destMap.get("destDir") != null) {
                        destDir = destMap.get("destDir").toString();
                    } else if (destMap.get("dest_dir") != null) {
                        destDir = destMap.get("dest_dir").toString();
                    } else if (destMap.get("path") != null) {
                        destDir = destMap.get("path").toString();
                    }
                }
                SecureExportConfig.DestinationConfig destConfig = new SecureExportConfig.DestinationConfig();
                destConfig.setDestDir(destDir != null && !destDir.trim().isEmpty() ? destDir : "secure-export");
                config.setDest(destConfig);

                if (configDetailsMap.containsKey("rules")) {
                    SecureExportConfig.RulesConfig rulesConfig = objectMapper.convertValue(configDetailsMap.get("rules"), SecureExportConfig.RulesConfig.class);
                    config.setRules(rulesConfig);
                }

                String executionId = "secure-export-" + secureExportSequence.getAndIncrement();
                taskExecutor.execute(() -> {
                    try {
                        secureExportService.processSecureExport(executionId, config);
                    } catch (Exception e) {
                        log.error("Secure Export failed in background task", e);
                    }
                });
                response.sendRedirect("/index.html?executionId=" + executionId);
                return;
            }


            if (configDetailsMap.containsKey("source")) {
                Map<String, Object> sourceMap = (Map<String, Object>) configDetailsMap.get("source");
                DbConnection sourceConnection = dbConnectionService.getConnection(Long.valueOf(sourceMap.get("id").toString()));
                DbConnectionRequest sourceReq = new DbConnectionRequest();
                sourceReq.setUrl(sourceConnection.getUrl());
                sourceReq.setUsername(sourceConnection.getUsername());
                sourceReq.setPassword(sourceConnection.getPassword());
                sourceReq.setSchema((String) sourceMap.get("schema"));
                sourceReq.setType("source");
                dynamicDataSourceService.updateConnection(sourceReq);
            }

            if (configDetailsMap.containsKey("dest")) {
                Map<String, Object> destMap = (Map<String, Object>) configDetailsMap.get("dest");
                DbConnection destConnection = dbConnectionService.getConnection(Long.valueOf(destMap.get("id").toString()));
                DbConnectionRequest destReq = new DbConnectionRequest();
                destReq.setUrl(destConnection.getUrl());
                destReq.setUsername(destConnection.getUsername());
                destReq.setPassword(destConnection.getPassword());
                destReq.setSchema((String) destMap.get("schema"));
                destReq.setType("destination");
                dynamicDataSourceService.updateConnection(destReq);
            }

            // Create a fresh job instance based on current DB connection/schema
            Job migrationJob = migrationJobFactory.createMigrationJob(jobRequest);

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

    @PostMapping("/json/start/{id}")
    public ResponseEntity<?> startJsonMigration(@PathVariable Long id) {
        try {
            log.info("Starting JSON migration job manually...");
            JobRequest jobRequest = jobApprovalService.getJob(id);
            System.out.println("#########################################");
            System.out.println(jobRequest.toString());
            System.out.println("#########################################");
            if (jobRequest == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Job not found"));
            }

            JsonMigrationConfig config = objectMapper.convertValue(jobRequest.getConfigDetails(), JsonMigrationConfig.class);
            System.out.println("#########################################");
            System.out.println("Job config Details :"+jobRequest.getConfigDetails().toString());
            System.out.println("ObjMapper config Details :"+objectMapper.convertValue(jobRequest.getConfigDetails(), JsonMigrationConfig.class).toString());
            System.out.println(config.toString());
            System.out.println("#########################################");
            // Execute the JSON migration process asynchronously
            String executionId = "json-" + jsonSequence.getAndIncrement();
            taskExecutor.execute(() -> {
                try {
                    jsonMigrationService.processMigrationAsync(executionId, config);
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

            Job mongoJob = migrationJobFactory.createMongoMigrationJob(jobRequest);

            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("jobName", "DBMigrationJob")
                    .addString("name", "DBMigrationJob")
                    .addLong("startTime", System.currentTimeMillis())
                    .addString("jobType", "MONGO")
                    .toJobParameters();

            taskExecutor.execute(() -> {
                try {
                    jobExecution = jobLauncher.run(mongoJob, jobParameters);
                } catch (Exception e) {
                    log.error("Mongo Migration failed in background task", e);
                }
            });

            if(jobExecution==null){
                Thread.sleep(500);
                if (jobExecution == null) {
                     jobExecution = jobExplorer.findRunningJobExecutions("DBMigrationJob").stream()
                             .max(Comparator.comparing(JobExecution::getId))
                             .orElse(null);
                }
            }

            String executionId = jobExecution != null ? jobExecution.getId().toString() : "error";
            log.info("Mongo Migration task launched with id: {}", executionId);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "executionId", executionId, "message", "Mongo Migration started"));

        } catch (Exception e) {
            log.error("Failed to start Mongo migration", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @GetMapping("/mongo/status/{executionId}")
    public ResponseEntity<?> getMongoStatus(@PathVariable String executionId) {
        return ResponseEntity.ok(getJobStatus(executionId)); // Route to standard status since it's a Batch job now!
    }

    @PostMapping("/secure-import/start/{id}")
    public ResponseEntity<?> startSecureImportMigration(@PathVariable Long id, @RequestBody(required = false) Map<String, String> payload) {
        try {
            log.info("Starting Secure Import job manually with ID: {}", id);
            JobRequest jobRequest = jobApprovalService.getJob(id);
            if (jobRequest == null) {
                return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Job not found"));
            }

            String secretKey = (payload != null) ? payload.get("secretKey") : null;

            Map<String, Object> configDetailsMap = objectMapper.convertValue(jobRequest.getConfigDetails(), Map.class);
            SecureImportConfig config = objectMapper.convertValue(configDetailsMap, SecureImportConfig.class);
            if (config == null) {
                config = new SecureImportConfig();
            }
            config.setJobName(jobRequest.getMigrationName());

            // Validate secret key first - throws IllegalArgumentException on invalid key
            secureImportService.validateSecretKey(config, secretKey);

            String executionId = "secure-import-" + secureImportSequence.getAndIncrement();
            final SecureImportConfig finalConfig = config;
            taskExecutor.execute(() -> {
                try {
                    secureImportService.processSecureImport(executionId, finalConfig, secretKey);
                } catch (Exception e) {
                    log.error("Secure Import failed in background task", e);
                }
            });

            log.info("Secure Import task launched with id: {}", executionId);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "executionId", executionId, "message", "Secure Import started"));

        } catch (IllegalArgumentException e) {
            log.warn("Secure Import key validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start Secure Import", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
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
            // Get last few instances from Spring Batch
            List<JobInstance> instances = jobExplorer.getJobInstances(jobName, 0, 20);

            for (JobInstance instance : instances) {
                List<JobExecution> executions = jobExplorer.getJobExecutions(instance);
                for (JobExecution execution : executions) {
                    Map<String, Object> execMap = new HashMap<>();
                    execMap.put("id", execution.getId().toString()); // Convert to String to match other types
                    execMap.put("status", execution.getStatus().toString());
                    execMap.put("startTime", execution.getStartTime() != null ? execution.getStartTime() : 0);
                    result.add(execMap);
                }
            }

            // Add JSON, Secure Export, and Secure Import executions
            result.addAll(jsonMigrationService.getAllExecutions());
            result.addAll(secureExportService.getAllExecutions());
            result.addAll(secureImportService.getAllExecutions());


            // Sort descending by ID (using string comparison for mixed types)
            result.sort((m1, m2) -> {
                String id1 = m1.get("id").toString();
                String id2 = m2.get("id").toString();

                // Try numeric sort if both are numbers (batch IDs)
                try {
                    long l1 = Long.parseLong(id1);
                    long l2 = Long.parseLong(id2);
                    return Long.compare(l2, l1);
                } catch (NumberFormatException e) {
                    // Fallback to string sort
                    return id2.compareTo(id1);
                }
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
        if (executionId.startsWith("json-")) {
            return jsonMigrationService.getProgress(executionId);
        } else if (executionId.startsWith("secure-export-")) {
            return secureExportService.getProgress(executionId);
        } else if (executionId.startsWith("secure-import-")) {
            return secureImportService.getProgress(executionId);
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
            if (stepName.startsWith("migrate_")) {
                String tableName = stepName.replace("migrate_", "");
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

        if (executionId.startsWith("json-")) {
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