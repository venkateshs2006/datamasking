package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.MongoSecureExportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MongoSecureExportJobRepository extends JpaRepository<MongoSecureExportJob, Long> {
    MongoSecureExportJob findByExecutionId(String executionId);
    List<MongoSecureExportJob> findByDepartmentIn(List<String> departments);
    List<MongoSecureExportJob> findAllByOrderByCreatedAtDesc();
}
