package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.repository.JobRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class JobApprovalService {
    
    private final JobRequestRepository jobRequestRepository;

    public JobRequest submitJob(JobRequest request) {
        request.setStatus("WAITING");
        request.setCreatedAt(System.currentTimeMillis());
        return jobRequestRepository.save(request);
    }

    public List<JobRequest> getAllJobs(List<String> departments) {
        if (!CollectionUtils.isEmpty(departments)) {
            List<String> lowerDepts = departments.stream()
                    .filter(d -> d != null && !d.trim().isEmpty())
                    .map(d -> d.trim().toLowerCase())
                    .toList();
            return jobRequestRepository.findByDepartmentInIgnoreCaseOrderByCreatedAtDesc(lowerDepts);
        }
        return jobRequestRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<JobRequest> getAllJobsForUser(List<String> departments, String username) {
        String cleanUser = username != null ? username.trim().toLowerCase() : null;
        if (!CollectionUtils.isEmpty(departments)) {
            List<String> lowerDepts = departments.stream()
                    .filter(d -> d != null && !d.trim().isEmpty())
                    .map(d -> d.trim().toLowerCase())
                    .toList();
            if (cleanUser != null && !cleanUser.isEmpty()) {
                return jobRequestRepository.findByDepartmentsOrSubmittedBy(lowerDepts, cleanUser);
            }
            return jobRequestRepository.findByDepartmentInIgnoreCaseOrderByCreatedAtDesc(lowerDepts);
        }
        if (cleanUser != null && !cleanUser.isEmpty()) {
            return jobRequestRepository.findBySubmittedByIgnoreCaseOrderByCreatedAtDesc(cleanUser);
        }
        return List.of();
    }

    public JobRequest getJob(Long id) {
        return jobRequestRepository.findById(id).orElse(null);
    }

    public JobRequest approveJob(Long id, String comments) {
        JobRequest job = getJob(id);
        if (job != null) {
            job.setStatus("APPROVED");
            job.setComments(comments);
            return jobRequestRepository.save(job);
        }
        return null;
    }

    public JobRequest rejectJob(Long id, String comments) {
        JobRequest job = getJob(id);
        if (job != null) {
            job.setStatus("REJECTED");
            job.setComments(comments);
            return jobRequestRepository.save(job);
        }
        return null;
    }
}