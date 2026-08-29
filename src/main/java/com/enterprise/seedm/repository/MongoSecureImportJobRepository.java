package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.MongoSecureImportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MongoSecureImportJobRepository extends JpaRepository<MongoSecureImportJob, Long> {
    MongoSecureImportJob findByExecutionId(String executionId);
    List<MongoSecureImportJob> findByDepartmentIn(List<String> departments);
    List<MongoSecureImportJob> findAllByOrderByCreatedAtDesc();
}
