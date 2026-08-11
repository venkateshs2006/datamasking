package com.enterprise.seedm.model;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;
import org.springframework.data.mongodb.core.schema.JsonSchemaObject;

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


    @Column(columnDefinition = "jsonb")
    private JsonNode properties;
    @Column(name = "config_details", columnDefinition = "json")
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