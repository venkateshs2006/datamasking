package com.enterprise.seedm.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "job_requests")
@Data
public class JobRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "migration_name", nullable = false)
    private String migrationName;

    @Column(name = "job_type", nullable = false)
    private String jobType; // "postgres", "mongo", "json", "SECURE_EXPORT"

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_details", columnDefinition = "jsonb")
    private Map<String, Object> configDetails;

    @Column(name = "status", nullable = false)
    private String status; // "WAITING", "APPROVED", "REJECTED"

    @Column(name = "comments")
    private String comments;

    @Column(name = "submitted_by")
    private String submittedBy;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "department")
    private String department;
}