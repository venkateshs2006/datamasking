package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.JsonSecureExportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JsonSecureExportJobRepository extends JpaRepository<JsonSecureExportJob, Long> {
    JsonSecureExportJob findByExecutionId(String executionId);
    List<JsonSecureExportJob> findByDepartmentIn(List<String> departments);
    List<JsonSecureExportJob> findAllByOrderByCreatedAtDesc();
}
