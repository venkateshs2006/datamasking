package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.SecureImportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SecureImportJobRepository extends JpaRepository<SecureImportJob, Long> {
    SecureImportJob findByExecutionId(String executionId);
    List<SecureImportJob> findAllByOrderByCreatedAtDesc();
}
