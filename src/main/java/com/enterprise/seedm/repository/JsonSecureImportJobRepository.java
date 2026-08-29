package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.JsonSecureImportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JsonSecureImportJobRepository extends JpaRepository<JsonSecureImportJob, Long> {
    JsonSecureImportJob findByExecutionId(String executionId);
    List<JsonSecureImportJob> findByDepartmentIn(List<String> departments);
    List<JsonSecureImportJob> findAllByOrderByCreatedAtDesc();
}
