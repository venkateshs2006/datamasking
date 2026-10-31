package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.CsvSecureExportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CsvSecureExportJobRepository extends JpaRepository<CsvSecureExportJob, Long> {
    CsvSecureExportJob findByExecutionId(String executionId);
    List<CsvSecureExportJob> findAllByOrderByCreatedAtDesc();
}
