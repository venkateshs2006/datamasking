package com.enterprise.seedm.repository;

import com.enterprise.seedm.model.JobCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobCheckpointRepository extends JpaRepository<JobCheckpoint, Long> {

    Optional<JobCheckpoint> findByJobExecutionIdAndTableNameAndPartitionId(
            Long jobExecutionId, String tableName, Integer partitionId);

    List<JobCheckpoint> findByJobExecutionId(Long jobExecutionId);

    List<JobCheckpoint> findByJobExecutionIdAndTableName(Long jobExecutionId, String tableName);
}
