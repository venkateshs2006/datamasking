package com.enterprise.seedm.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "migration_job")
@Data
public class MigrationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, unique = true)
    private String jobId;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "source_db_type")
    private String sourceDbType;

    @Column(name = "target_db_type")
    private String targetDbType;

    @Column(name = "job_status")
    private String jobStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}