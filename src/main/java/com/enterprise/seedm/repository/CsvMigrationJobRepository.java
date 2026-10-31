package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.CsvMigrationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CsvMigrationJobRepository extends JpaRepository<CsvMigrationJob, Long> {
    CsvMigrationJob findByExecutionId(String executionId);
    List<CsvMigrationJob> findAllByOrderByCreatedAtDesc();
}
