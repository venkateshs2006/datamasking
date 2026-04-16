package com.enterprise.seedm.model;

import lombok.Data;

@Data
public class JobRequest {
    private String id;
    private String jobType;
    private Object configDetails; // JSON or Map representation of all rules and connections
    private String status; // WAITING, APPROVED, REJECTED
    private String comments;
    private String submittedBy;
    private long createdAt;
}
