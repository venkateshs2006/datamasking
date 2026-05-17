package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.MigrationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, Long> {
    Optional<MigrationJob> findByJobId(String jobId);
    List<MigrationJob> findBySourceDbType(String sourceDbType);
}
