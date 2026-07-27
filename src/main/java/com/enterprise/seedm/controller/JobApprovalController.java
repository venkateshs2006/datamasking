package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.service.CosConnectionService;
import com.enterprise.seedm.service.DbConnectionService;
import com.enterprise.seedm.service.JobApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobApprovalController {

    private final JobApprovalService jobApprovalService;
    private final DbConnectionService dbConnectionService;
    private final CosConnectionService cosConnectionService;
    private final ObjectMapper objectMapper;

    @PostMapping("/request")
    public ResponseEntity<?> submitJobRequest(@RequestBody JobRequest request, HttpServletRequest servletRequest) {
        HttpSession session = servletRequest.getSession(false);
        if (session == null) {
            return ResponseEntity.status(401).build();
        }
        String user = (String) session.getAttribute("user");
        request.setSubmittedBy(user);

        if (request.getConfigDetails() != null) {
            Map<String, Object> configDetailsMap = objectMapper.convertValue(request.getConfigDetails(), Map.class);
            String department = null;

            // Try to get department from source connection
            if (configDetailsMap.containsKey("source")) {
                Map<String, Object> sourceMap = (Map<String, Object>) configDetailsMap.get("source");
                if (sourceMap.containsKey("id")) {
                    DbConnection sourceConnection = dbConnectionService.getConnection(Long.valueOf(sourceMap.get("id").toString()));
                    if (sourceConnection != null) {
                        department = sourceConnection.getDepartment();
                    }
                }
            }

            // If department not found, try destination connection
            if (department == null && configDetailsMap.containsKey("dest")) {
                Map<String, Object> destMap = (Map<String, Object>) configDetailsMap.get("dest");
                if (destMap.containsKey("id")) {
                    DbConnection destConnection = dbConnectionService.getConnection(Long.valueOf(destMap.get("id").toString()));
                    if (destConnection != null) {
                        department = destConnection.getDepartment();
                    }
                }
            }

            // If department still not found, try storage connection
            if (department == null && configDetailsMap.containsKey("storage")) {
                Map<String, Object> storageMap = (Map<String, Object>) configDetailsMap.get("storage");
                if (storageMap.containsKey("id") && storageMap.get("id") != null) {
                    CosConnection cosConnection = cosConnectionService.getConnection(Long.valueOf(storageMap.get("id").toString()));
                    if (cosConnection != null) {
                        department = cosConnection.getDepartment();
                    }
                }
            }
            request.setDepartment(department);
        }

        JobRequest submittedJob = jobApprovalService.submitJob(request);
        return ResponseEntity.ok(submittedJob);
    }

    @GetMapping
    public List<JobRequest> getAllJobs(@RequestParam(required = false) String department) {
        List<String> depts = department != null ? Arrays.asList(department.split(",")) : null;
        return jobApprovalService.getAllJobs(depts);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getJob(@PathVariable Long id) {
        JobRequest job = jobApprovalService.getJob(id);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveJob(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        JobRequest job = jobApprovalService.approveJob(id, payload.get("comments"));
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectJob(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        JobRequest job = jobApprovalService.rejectJob(id, payload.get("comments"));
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }
}