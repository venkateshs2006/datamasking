package com.enterprise.seedm.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "mongo_migration_details")
@Data
public class MongoMigrationDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    @Column(name = "collection_name")
    private String collectionName;

    @Column(name = "source_count")
    private Long sourceCount;

    @Column(name = "migrated_count")
    private Long migratedCount;

    @Column(name = "failed_count")
    private Long failedCount;

    @Column(name = "status")
    private String status;

    @Column(name = "remarks")
    private String remarks;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}