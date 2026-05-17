package com.enterprise.seedm.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MongoDashboardResponse {

    private String jobId;
    private String projectId;
    private String jobStatus;
    private String sourceDbType;
    private String targetDbType;
    private LocalDateTime createdAt;
    private List<MongoMigrationDetails> details;
}