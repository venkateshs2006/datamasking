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

import java.util.ArrayList;
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
    private final com.enterprise.seedm.repository.UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/request")
    public ResponseEntity<?> submitJobRequest(@RequestBody Map<String, Object> requestPayload, HttpServletRequest servletRequest) {
        HttpSession session = servletRequest.getSession(false);
        if (session == null) {
            return ResponseEntity.status(401).build();
        }
        String user = (String) session.getAttribute("user");

        JobRequest request = objectMapper.convertValue(requestPayload, JobRequest.class);
        request.setSubmittedBy(user);

        if (request.getConfigDetails() != null) {
            Map<String, Object> configDetailsMap = objectMapper.convertValue(request.getConfigDetails(), Map.class);
            String department = null;

            // Try to get department from source connection
            if (configDetailsMap.containsKey("source")) {
                Map<String, Object> sourceMap = (Map<String, Object>) configDetailsMap.get("source");
                if (sourceMap.containsKey("id") && sourceMap.get("id") != null) {
                    try {
                        DbConnection sourceConnection = dbConnectionService.getConnection(Long.valueOf(sourceMap.get("id").toString()));
                        if (sourceConnection != null) {
                            department = sourceConnection.getDepartment();
                        }
                    } catch (Exception ignored) {}
                }
                if (department == null && sourceMap.containsKey("cosId") && sourceMap.get("cosId") != null) {
                    try {
                        CosConnection cosConnection = cosConnectionService.getConnection(Long.valueOf(sourceMap.get("cosId").toString()));
                        if (cosConnection != null) {
                            department = cosConnection.getDepartment();
                        }
                    } catch (Exception ignored) {}
                }
            }

            // If department not found, try destination connection
            if (department == null && configDetailsMap.containsKey("dest")) {
                Map<String, Object> destMap = (Map<String, Object>) configDetailsMap.get("dest");
                if (destMap.containsKey("id") && destMap.get("id") != null) {
                    DbConnection destConnection = dbConnectionService.getConnection(Long.valueOf(destMap.get("id").toString()));
                    if (destConnection != null) {
                        department = destConnection.getDepartment();
                    }
                } else if (destMap.containsKey("cosId") && destMap.get("cosId") != null) {
                    CosConnection cosConnection = cosConnectionService.getConnection(Long.valueOf(destMap.get("cosId").toString()));
                    if (cosConnection != null) {
                        department = cosConnection.getDepartment();
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
            if (department != null) {
                request.setDepartment(department);
            }
        }

        JobRequest submittedJob = jobApprovalService.submitJob(request);
        return ResponseEntity.ok(submittedJob);
    }

    @GetMapping
    public List<JobRequest> getAllJobs(@RequestParam(required = false) String department, HttpServletRequest servletRequest) {
        HttpSession session = servletRequest.getSession(false);
        String username = session != null ? (String) session.getAttribute("user") : null;
        String role = session != null ? (String) session.getAttribute("role") : null;
        boolean isAdmin = role != null && "ADMIN".equalsIgnoreCase(role);

        if (isAdmin) {
            if (department != null && !department.trim().isEmpty() && !"ALL".equalsIgnoreCase(department.trim())) {
                List<String> depts = Arrays.stream(department.trim().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                return jobApprovalService.getAllJobs(depts);
            }
            return jobApprovalService.getAllJobs(null);
        }

        List<String> userDepts = new ArrayList<>();
        if (session != null) {
            Object deptsObj = session.getAttribute("departments");
            if (deptsObj instanceof List) {
                for (Object d : (List<?>) deptsObj) {
                    if (d instanceof com.enterprise.seedm.model.Department) {
                        userDepts.add(((com.enterprise.seedm.model.Department) d).getName());
                    } else if (d instanceof String) {
                        userDepts.add((String) d);
                    }
                }
            }
            if (userDepts.isEmpty() && session.getAttribute("department") != null) {
                String d = (String) session.getAttribute("department");
                if (!"NONE".equalsIgnoreCase(d) && !"ALL".equalsIgnoreCase(d)) {
                    userDepts.add(d);
                }
            }
            if (username != null) {
                com.enterprise.seedm.model.AppUser user = userRepository.findByUsername(username);
                if (user != null && user.getDepartments() != null && !user.getDepartments().isEmpty()) {
                    userDepts.clear();
                    for (com.enterprise.seedm.model.Department d : user.getDepartments()) {
                        userDepts.add(d.getName());
                    }
                }
            }
        }

        if (department != null && !department.trim().isEmpty() && !"ALL".equalsIgnoreCase(department.trim())) {
            List<String> requestedDepts = Arrays.stream(department.trim().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            List<String> allowedDepts = requestedDepts.stream()
                    .filter(rd -> userDepts.stream().anyMatch(ud -> ud.equalsIgnoreCase(rd)))
                    .toList();
            if (allowedDepts.isEmpty()) {
                return List.of();
            }
            return jobApprovalService.getAllJobs(allowedDepts);
        }

        if (userDepts.isEmpty() && (username == null || username.trim().isEmpty())) {
            return List.of();
        }
        return jobApprovalService.getAllJobsForUser(userDepts, username);
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