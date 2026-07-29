package coms.seedm.controller;

import com.seedm.batch.SeedmJobRunner;
import com.seedm.config.SeedmRequest;
import jakarta.validation.Valid;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class SeedmController {

    private final SeedmJobRunner jobRunner;

    public SeedmController(SeedmJobRunner jobRunner) {
        this.jobRunner = jobRunner;
    }

    /**
     * Accepts the exact request shape described in the project spec:
     * { "source": {...}, "storage": {...}, "rules": {...} }
     *
     * Launches the masking/backup job asynchronously and returns immediately
     * with the Spring Batch job execution id + starting status so the caller
     * can poll Spring Batch's own tables (or a future /status endpoint) for
     * progress.
     */
    @PostMapping("/api/seedm/run")
    public ResponseEntity<Map<String, Object>> run(@Valid @RequestBody SeedmRequest request) throws Exception {
        JobExecution execution = jobRunner.run(request);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobExecutionId", execution.getId());
        body.put("jobInstanceId", execution.getJobInstance().getInstanceId());
        body.put("status", execution.getStatus());
        body.put("outputPath", request.getStorage().getPath());
        body.put("tables", request.getRules().getTargetTables());

        HttpStatus httpStatus = execution.getStatus() == BatchStatus.FAILED
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.ACCEPTED;

        return ResponseEntity.status(httpStatus).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleFailure(IllegalStateException e) {
        return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
    }
}
