package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JobRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class JobApprovalService {
    private final Map<String, JobRequest> jobs = new ConcurrentHashMap<>();

    public JobRequest submitJob(JobRequest request) {
        request.setId(UUID.randomUUID().toString());
        request.setStatus("WAITING");
        request.setCreatedAt(System.currentTimeMillis());
        jobs.put(request.getId(), request);
        return request;
    }

    public List<JobRequest> getAllJobs() {
        return new ArrayList<>(jobs.values()).stream()
                .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
                .collect(Collectors.toList());
    }

    public JobRequest getJob(String id) {
        return jobs.get(id);
    }

    public JobRequest approveJob(String id, String comments) {
        JobRequest job = jobs.get(id);
        if (job != null) {
            job.setStatus("APPROVED");
            job.setComments(comments);
        }
        return job;
    }

    public JobRequest rejectJob(String id, String comments) {
        JobRequest job = jobs.get(id);
        if (job != null) {
            job.setStatus("REJECTED");
            job.setComments(comments);
        }
        return job;
    }
}
