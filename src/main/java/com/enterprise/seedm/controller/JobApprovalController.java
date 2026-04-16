package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.service.JobApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobApprovalController {

    private final JobApprovalService jobApprovalService;

    @PostMapping("/request")
    public ResponseEntity<?> submitJobRequest(@RequestBody JobRequest request, HttpServletRequest servletRequest) {
        String user = (String) servletRequest.getSession().getAttribute("user");
        request.setSubmittedBy(user);
        JobRequest submittedJob = jobApprovalService.submitJob(request);
        return ResponseEntity.ok(submittedJob);
    }

    @GetMapping
    public List<JobRequest> getAllJobs() {
        return jobApprovalService.getAllJobs();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getJob(@PathVariable String id) {
        JobRequest job = jobApprovalService.getJob(id);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveJob(@PathVariable String id, @RequestBody Map<String, String> payload) {
        JobRequest job = jobApprovalService.approveJob(id, payload.get("comments"));
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectJob(@PathVariable String id, @RequestBody Map<String, String> payload) {
        JobRequest job = jobApprovalService.rejectJob(id, payload.get("comments"));
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }
}
