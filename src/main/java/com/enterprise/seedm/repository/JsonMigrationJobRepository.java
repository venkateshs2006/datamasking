package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.JsonMigrationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JsonMigrationJobRepository extends JpaRepository<JsonMigrationJob, Long> {
    JsonMigrationJob findByExecutionId(String executionId);
    List<JsonMigrationJob> findAllByOrderByCreatedAtDesc();
}
