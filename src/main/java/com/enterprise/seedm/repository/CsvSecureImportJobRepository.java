package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.CsvSecureImportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CsvSecureImportJobRepository extends JpaRepository<CsvSecureImportJob, Long> {
    CsvSecureImportJob findByExecutionId(String executionId);
    List<CsvSecureImportJob> findAllByOrderByCreatedAtDesc();
}
