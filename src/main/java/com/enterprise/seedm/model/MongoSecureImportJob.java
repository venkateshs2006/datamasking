package com.enterprise.seedm.model;

import lombok.Data;
import jakarta.persistence.*;

@Entity
@Table(name = "mongo_secure_import_jobs")
@Data
public class MongoSecureImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, unique = true)
    private String executionId;

    @Column(name = "job_name", nullable = false)
    private String jobName;

    @Column(name = "status", nullable = false)
    private String status; // PENDING, RUNNING, COMPLETED, FAILED

    @Column(name = "config_details", columnDefinition = "TEXT")
    private String configDetails;

    @Column(name = "submitted_by")
    private String submittedBy;

    @Column(name = "department")
    private String department;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "completed_at")
    private Long completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "total_collections")
    private Integer totalCollections = 0;

    @Column(name = "processed_collections")
    private Integer processedCollections = 0;

    @Column(name = "total_records")
    private Long totalRecords = 0L;

    @Column(name = "progress_details", columnDefinition = "TEXT")
    private String progressDetails;
}
