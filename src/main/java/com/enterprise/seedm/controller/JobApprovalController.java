package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.service.JobApprovalService;
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

    @PostMapping("/request")
    public ResponseEntity<?> submitJobRequest(@RequestBody JobRequest request, HttpServletRequest servletRequest) {
        HttpSession session = servletRequest.getSession(false);
        if (session == null) {
            return ResponseEntity.status(401).build();
        }
        String user = (String) session.getAttribute("user");
        List<String> departments = (List<String>) session.getAttribute("departments");
        
        request.setSubmittedBy(user);
        if (departments != null && !departments.isEmpty()) {
            request.setDepartment(departments.get(0));
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