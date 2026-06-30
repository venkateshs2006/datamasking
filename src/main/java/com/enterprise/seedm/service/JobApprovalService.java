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
               return jobRequestRepository.findByDepartmentInOrderByCreatedAtDesc(departments);
        }
        return jobRequestRepository.findAllByOrderByCreatedAtDesc();
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