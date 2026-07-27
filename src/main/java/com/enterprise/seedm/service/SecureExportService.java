package com.enterprise.seedm.service;

import com.enterprise.seedm.dto.SecureExportRequest;
import com.enterprise.seedm.model.SecureExportJob;
import com.enterprise.seedm.repository.SecureExportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SecureExportService {

    @Autowired
    private SecureExportJobRepository secureExportJobRepository;

    @Autowired
    private ObjectMapper objectMapper;

    public void createJob(SecureExportRequest request) {
        try {
            SecureExportJob job = new SecureExportJob();
            job.setSourceConnectionId(request.getSourceConnectionId());
            job.setSchemaName(request.getSchema());
            job.setStorageType(request.getStorageType());
            if ("cos".equals(request.getStorageType())) {
                job.setCosConnectionId(request.getCosConnectionId());
            } else {
                job.setLocalPath(request.getLocalPath());
            }
            job.setTables(objectMapper.writeValueAsString(request.getTables()));
            job.setRules(objectMapper.writeValueAsString(request.getRules()));
            job.setStatus("PENDING_APPROVAL");
            secureExportJobRepository.save(job);
        } catch (Exception e) {
            throw new RuntimeException("Could not create job", e);
        }
    }

    public List<SecureExportJob> getAllJobs() {
        return secureExportJobRepository.findAll();
    }

    public void approveJob(Long id) {
        SecureExportJob job = secureExportJobRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Job not found"));
        job.setStatus("APPROVED");
        secureExportJobRepository.save(job);
    }
}
