package com.enterprise.seedm.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Audit Log Entity
 * Immutable record of all job executions and operations
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_job_execution", columnList = "jobExecutionId"),
        @Index(name = "idx_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobExecutionId;

    @Column(nullable = false)
    private String jobName;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String eventDescription;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column
    private String username;

    @Column
    private String tableName;

    @Column
    private Long rowsProcessed;

    @Column
    private String severity;

    @Column(columnDefinition = "TEXT")
    private String additionalInfo;
}