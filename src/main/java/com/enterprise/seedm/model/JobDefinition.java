package com.enterprise.seedm.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Job Definition Entity
 * Stores migration job configurations and metadata
 */
@Entity
@Table(name = "job_definitions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String jobName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String configYaml;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @Column
    private String createdBy;

    @Column
    private String description;

    @Column
    private Integer chunkSize;

    @Column
    private Integer threadCount;

    @Column
    private String batchMode;

    public enum JobStatus {
        CONFIGURED,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
