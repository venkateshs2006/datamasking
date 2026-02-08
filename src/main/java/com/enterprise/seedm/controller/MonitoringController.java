package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.AuditLog;
import com.enterprise.seedm.model.JobCheckpoint;
import com.enterprise.seedm.repository.AuditLogRepository;
import com.enterprise.seedm.repository.JobCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Monitoring Controller for Real-time Job Progress
 * Provides Server-Sent Events (SSE) for live updates
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class MonitoringController {

    private final JobCheckpointRepository checkpointRepository;
    private final AuditLogRepository auditLogRepository;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Stream job progress via Server-Sent Events
     */
    @GetMapping(value = "/stream/{jobExecutionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable Long jobExecutionId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError((e) -> emitters.remove(emitter));

        // Start streaming progress
        executor.execute(() -> streamJobProgress(emitter, jobExecutionId));

        return emitter;
    }

    /**
     * Stream job progress updates
     */
    private void streamJobProgress(SseEmitter emitter, Long jobExecutionId) {
        try {
            while (true) {
                // Get current progress
                Map<String, Object> progress = getJobProgress(jobExecutionId);

                // Send to client
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(progress));

                // Wait before next update
                Thread.sleep(500);
            }
        } catch (IOException | InterruptedException e) {
            log.debug("SSE stream closed for job {}", jobExecutionId);
            emitter.complete();
        }
    }

    /**
     * Get current job progress
     */
    @GetMapping("/{jobExecutionId}/progress")
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable Long jobExecutionId) {
        Map<String, Object> progress = getJobProgress(jobExecutionId);
        return ResponseEntity.ok(progress);
    }

    /**
     * Get job checkpoints
     */
    @GetMapping("/{jobExecutionId}/checkpoints")
    public ResponseEntity<List<JobCheckpoint>> getCheckpoints(@PathVariable Long jobExecutionId) {
        List<JobCheckpoint> checkpoints = checkpointRepository.findByJobExecutionId(jobExecutionId);
        return ResponseEntity.ok(checkpoints);
    }

    /**
     * Get audit logs for a job
     */
    @GetMapping("/{jobExecutionId}/audit")
    public ResponseEntity<List<AuditLog>> getAuditLogs(@PathVariable Long jobExecutionId) {
        List<AuditLog> logs = auditLogRepository.findByJobExecutionId(jobExecutionId);
        return ResponseEntity.ok(logs);
    }

    /**
     * Calculate job progress metrics
     */
    private Map<String, Object> getJobProgress(Long jobExecutionId) {
        List<JobCheckpoint> checkpoints = checkpointRepository.findByJobExecutionId(jobExecutionId);

        Map<String, Object> progress = new HashMap<>();
        progress.put("jobExecutionId", jobExecutionId);
        progress.put("timestamp", System.currentTimeMillis());

        if (checkpoints.isEmpty()) {
            progress.put("totalRowsProcessed", 0);
            progress.put("tablesInProgress", 0);
            progress.put("rowsPerSecond", 0);
            return progress;
        }

        // Calculate metrics
        long totalRows = checkpoints.stream()
                .mapToLong(JobCheckpoint::getTotalRowsProcessed)
                .sum();

        long tablesInProgress = checkpoints.stream()
                .filter(c -> "IN_PROGRESS".equals(c.getStatus()))
                .count();

        // Calculate rows per second (simplified)
        double rowsPerSecond = totalRows / Math.max(1,
                (System.currentTimeMillis() - checkpoints.get(0).getUpdatedAt()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()) / 1000.0);

        progress.put("totalRowsProcessed", totalRows);
        progress.put("tablesInProgress", tablesInProgress);
        progress.put("rowsPerSecond", (int) rowsPerSecond);
        progress.put("checkpoints", checkpoints);

        return progress;
    }
}