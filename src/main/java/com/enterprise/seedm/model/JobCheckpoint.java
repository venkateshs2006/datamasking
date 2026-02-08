package com.enterprise.seedm.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Job Checkpoint Entity
 * Stores the last processed offset for each table/partition
 * Enables intelligent resume on failure
 */
@Entity
@Table(name = "job_checkpoints",
        uniqueConstraints = @UniqueConstraint(columnNames = {"jobExecutionId", "tableName", "partitionId"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobExecutionId;

    @Column(nullable = false)
    private String tableName;

    @Column(nullable = false)
    private Integer partitionId;

    @Column(nullable = false)
    private Long lastReadOffset;

    @Column(nullable = false)
    private Long totalRowsProcessed;

    @Column
    private String lastProcessedKey;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private String status;
}
