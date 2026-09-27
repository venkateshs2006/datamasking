package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.BulkDumpConfig;
import com.enterprise.seedm.model.BulkDumpProgress;
import com.enterprise.seedm.model.BulkDumpTableConfig;
import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.service.BulkDataDumpService;
import com.enterprise.seedm.service.DbConnectionService;
import com.enterprise.seedm.service.JobApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/bulk-dump")
@RequiredArgsConstructor
@Slf4j
public class BulkDumpController {

    private final BulkDataDumpService bulkDataDumpService;
    private final JobApprovalService jobApprovalService;
    private final DbConnectionService dbConnectionService;
    private final com.enterprise.seedm.service.CosConnectionService cosConnectionService;
    private final ObjectMapper objectMapper;

    /**
     * Step 2 & 3: Fetch list of tables with existing count
     */
    @PostMapping("/tables")
    public ResponseEntity<?> getTablesWithCounts(@RequestBody Map<String, Object> request) {
        try {
            String dbType = (String) request.getOrDefault("dbType", "sql");
            Object connIdObj = request.get("connectionId");
            Long connectionId = null;
            if (connIdObj != null) {
                connectionId = Long.parseLong(connIdObj.toString());
            }

            String schemaOrDb = (String) request.get("schema");
            if (schemaOrDb == null) {
                schemaOrDb = (String) request.get("database");
            }
            String dirPath = (String) request.get("dirPath");

            List<BulkDumpTableConfig> tables = bulkDataDumpService.discoverTablesWithCounts(
                    dbType, connectionId, schemaOrDb, dirPath);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "dbType", dbType,
                    "totalTables", tables.size(),
                    "tables", tables
            ));
        } catch (Exception e) {
            log.error("Failed to discover tables and counts", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * Step 4: Send for Approval
     */
    @PostMapping("/request")
    public ResponseEntity<?> sendForApproval(@RequestBody Map<String, Object> payload, HttpServletRequest servletRequest) {
        try {
            HttpSession session = servletRequest.getSession(false);
            String user = (session != null && session.getAttribute("user") != null)
                    ? (String) session.getAttribute("user")
                    : "system";

            BulkDumpConfig config = objectMapper.convertValue(payload.get("config"), BulkDumpConfig.class);
            if (config == null) {
                config = objectMapper.convertValue(payload, BulkDumpConfig.class);
            }

            String jobName = config.getJobName();
            if (jobName == null || jobName.trim().isEmpty()) {
                jobName = "Bulk Dump - " + (config.getDbType() != null ? config.getDbType().toUpperCase() : "DB") + " - " + System.currentTimeMillis();
            }

            String department = config.getDepartment();
            if ((department == null || department.trim().isEmpty()) && config.getConnectionId() != null) {
                try {
                    var conn = dbConnectionService.getConnection(config.getConnectionId());
                    if (conn != null && conn.getDepartment() != null && !conn.getDepartment().trim().isEmpty()) {
                        department = conn.getDepartment();
                    }
                } catch (Exception ignored) {}
            }
            if ((department == null || department.trim().isEmpty()) && config.getCosId() != null) {
                try {
                    var cos = cosConnectionService.getConnection(config.getCosId());
                    if (cos != null && cos.getDepartment() != null && !cos.getDepartment().trim().isEmpty()) {
                        department = cos.getDepartment();
                    }
                } catch (Exception ignored) {}
            }
            if ((department == null || department.trim().isEmpty()) && session != null) {
                department = (String) session.getAttribute("department");
            }
            if (department == null || department.trim().isEmpty()) {
                department = "Operations";
            }

            String jobType = "BULK_DUMP_" + (config.getDbType() != null ? config.getDbType().toUpperCase() : "SQL");

            JobRequest jobRequest = new JobRequest();
            jobRequest.setMigrationName(jobName);
            jobRequest.setJobType(jobType);
            jobRequest.setStatus("WAITING");
            jobRequest.setSubmittedBy(user);
            jobRequest.setDepartment(department);
            jobRequest.setCreatedAt(System.currentTimeMillis());

            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = objectMapper.convertValue(config, Map.class);

            // Populate source and dest for seamless display on jobs.html
            Map<String, Object> sourceMap = new LinkedHashMap<>();
            sourceMap.put("id", config.getConnectionId() != null ? config.getConnectionId() : config.getCosId());
            sourceMap.put("url", config.getUrl() != null ? config.getUrl() : "Bulk Data Generator");
            sourceMap.put("schema", config.getSchema() != null ? config.getSchema() : config.getDatabase());
            configMap.put("source", sourceMap);

            Map<String, Object> destMap = new LinkedHashMap<>();
            destMap.put("id", config.getConnectionId() != null ? config.getConnectionId() : config.getCosId());
            destMap.put("url", config.getUrl() != null ? config.getUrl() : (config.getDirPath() != null ? config.getDirPath() : "Target Database"));
            destMap.put("schema", config.getSchema() != null ? config.getSchema() : config.getDatabase());
            configMap.put("dest", destMap);

            jobRequest.setConfigDetails(configMap);

            JobRequest saved = jobApprovalService.submitJob(jobRequest);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "jobId", saved.getId() != null ? saved.getId() : 0L,
                    "jobStatus", saved.getStatus() != null ? saved.getStatus() : "WAITING",
                    "jobType", saved.getJobType() != null ? saved.getJobType() : "BULK_DUMP_SQL",
                    "migrationName", saved.getMigrationName() != null ? saved.getMigrationName() : "Bulk Dump",
                    "message", "Job submitted for approval successfully"
            ));

        } catch (Exception e) {
            log.error("Failed to submit bulk dump job for approval", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "ERROR", "message", e.getMessage() != null ? e.getMessage() : "Error occurred"));
        }
    }

    /**
     * Helper / Step 5: Quick approve (for approvers / admin or instant testing)
     */
    @PostMapping("/approve/{jobId}")
    public ResponseEntity<?> approveJob(@PathVariable Long jobId, @RequestBody(required = false) Map<String, String> payload) {
        String comments = payload != null ? payload.getOrDefault("comments", "Approved for setup execution") : "Approved for setup execution";
        JobRequest approved = jobApprovalService.approveJob(jobId, comments);
        if (approved == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Job not found"));
        }
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "jobId", approved.getId(),
                "jobStatus", approved.getStatus(),
                "message", "Job has been approved successfully. Button can now run setup."
        ));
    }

    /**
     * Check approval status of a job
     */
    @GetMapping("/job/{jobId}")
    public ResponseEntity<?> getJobDetails(@PathVariable Long jobId) {
        JobRequest job = jobApprovalService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Job not found"));
        }
        return ResponseEntity.ok(job);
    }

    /**
     * Step 6: Run Setup -> Start dumping database tables based on count
     */
    @PostMapping("/start/{jobId}")
    public ResponseEntity<?> startBulkDump(@PathVariable Long jobId) {
        try {
            JobRequest job = jobApprovalService.getJob(jobId);
            if (job == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Job not found: " + jobId));
            }

            if (!"APPROVED".equalsIgnoreCase(job.getStatus())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                        "status", "ERROR",
                        "message", "Job is not approved yet. Current status: " + job.getStatus()
                ));
            }

            BulkDumpConfig config = objectMapper.convertValue(job.getConfigDetails(), BulkDumpConfig.class);
            if (config == null || config.getTables() == null || config.getTables().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                        "status", "ERROR",
                        "message", "Invalid job configuration: no tables defined"
                ));
            }

            String executionId = bulkDataDumpService.generateExecutionId();
            bulkDataDumpService.startBulkDump(executionId, jobId, config);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "executionId", executionId,
                    "jobId", jobId,
                    "message", "Bulk data dump execution started"
            ));

        } catch (Exception e) {
            log.error("Failed to start bulk dump for job {}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * Step 6: Poll status for popup progress bar
     */
    @GetMapping({"/status/{executionId}", "/progress/{executionId}"})
    public ResponseEntity<?> getExecutionStatus(@PathVariable String executionId) {
        BulkDumpProgress progress = bulkDataDumpService.getProgress(executionId);
        if (progress == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Execution not found: " + executionId));
        }
        return ResponseEntity.ok(progress);
    }

    /**
     * Step 7: Execution Report with sample data
     */
    @GetMapping("/report/{executionId}")
    public ResponseEntity<?> getExecutionReport(@PathVariable String executionId) {
        BulkDumpProgress progress = bulkDataDumpService.getProgress(executionId);
        if (progress == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Execution not found: " + executionId));
        }

        long durationSec = (progress.getEndTime() > 0 && progress.getStartTime() > 0)
                ? (progress.getEndTime() - progress.getStartTime()) / 1000
                : (System.currentTimeMillis() - progress.getStartTime()) / 1000;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", progress.getStatus());
        report.put("executionId", progress.getExecutionId());
        report.put("jobId", progress.getJobId() != null ? progress.getJobId() : 0);
        report.put("dbType", progress.getDbType() != null ? progress.getDbType() : "SQL");
        report.put("totalTables", progress.getTotalTables());
        report.put("processedTables", progress.getProcessedTables());
        report.put("totalTargetRecords", progress.getTotalTargetRecords());
        report.put("totalInsertedRecords", progress.getTotalInsertedRecords());
        report.put("durationSeconds", durationSec);
        report.put("tableSummary", progress.getTableProgress());
        report.put("sampleDataReport", progress.getSampleDataReport());
        report.put("logs", progress.getLogs());

        return ResponseEntity.ok(report);
    }
}
