package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.MongoMigrationDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MongoMigrationDetailsRepository extends JpaRepository<MongoMigrationDetails, Long> {
    List<MongoMigrationDetails> findByJobId(String jobId);
    Optional<MongoMigrationDetails> findByJobIdAndCollectionName(String jobId, String collectionName);
}