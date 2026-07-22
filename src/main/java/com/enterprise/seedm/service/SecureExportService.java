package com.enterprise.seedm.service;

import com.enterprise.seedm.dto.SecureExportRequest;
import com.enterprise.seedm.model.SecureExportJob;
import com.enterprise.seedm.repository.SecureExportJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SecureExportService {

    @Autowired
    private SecureExportJobRepository secureExportJobRepository;

    public void createJob(SecureExportRequest request) {
        SecureExportJob job = new SecureExportJob();
        job.setSourceConnectionId(request.getSourceConnectionId());
        job.setStorageType(request.getStorageType());
        if ("cos".equals(request.getStorageType())) {
            job.setCosConnectionId(request.getCosConnectionId());
        } else {
            job.setLocalPath(request.getLocalPath());
        }
        job.setStatus("PENDING_APPROVAL");
        secureExportJobRepository.save(job);
    }
}
