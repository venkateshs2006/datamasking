package com.enterprise.seedm.model;

import lombok.Data;
import jakarta.persistence.*;

@Entity
@Table(name = "csv_migration_jobs")
@Data
public class CsvMigrationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, unique = true)
    private String executionId;

    @Column(name = "job_name")
    private String jobName;

    @Column(name = "status", nullable = false)
    private String status; // PENDING, RUNNING, COMPLETED, FAILED

    @Column(name = "total_files")
    private Integer totalFiles = 0;

    @Column(name = "processed_files")
    private Integer processedFiles = 0;

    @Column(name = "total_records")
    private Long totalRecords = 0L;

    @Column(name = "progress_details", columnDefinition = "TEXT")
    private String progressDetails;

    @Column(name = "config_details", columnDefinition = "TEXT")
    private String configDetails;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "completed_at")
    private Long completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
