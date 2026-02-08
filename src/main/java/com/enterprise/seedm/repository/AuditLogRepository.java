package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByJobExecutionId(Long jobExecutionId);

    List<AuditLog> findByJobNameOrderByTimestampDesc(String jobName);

    List<AuditLog> findByTimestampBetween(LocalDateTime start, LocalDateTime end);
}