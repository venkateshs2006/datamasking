package com.enterprise.seedm.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;

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
    private String jobType; // "postgres", "mongo", "json"

    @Column(name = "config_details", columnDefinition = "TEXT")
    private String configDetailsJson; // Stores the JSON representation

    @Column(name = "status", nullable = false)
    private String status; // "WAITING", "APPROVED", "REJECTED"

    @Column(name = "comments")
    private String comments;

    @Column(name = "submitted_by")
    private String submittedBy;

    @Column(name = "created_at")
    private long createdAt;

    @Transient
    private Object configDetails; // For passing to/from frontend

    // Helper methods to handle JSON conversion
    @PostLoad
    @PostPersist
    @PostUpdate
    private void parseJson() {
        if (this.configDetailsJson != null) {
            try {
                ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                this.configDetails = mapper.readValue(this.configDetailsJson, Object.class);
            } catch (JsonProcessingException e) {
                this.configDetails = null;
            }
        }
    }

    @PrePersist
    @PreUpdate
    private void generateJson() {
        if (this.configDetails != null) {
            try {
                ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                this.configDetailsJson = mapper.writeValueAsString(this.configDetails);
            } catch (JsonProcessingException e) {
                this.configDetailsJson = null;
            }
        }
    }
}