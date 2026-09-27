package com.enterprise.seedm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkDumpConfig {
    /**
     * "sql" / "postgres", "mongo", "json"
     */
    @com.fasterxml.jackson.annotation.JsonAlias({"workflowType", "type", "databaseType"})
    private String dbType;

    @com.fasterxml.jackson.annotation.JsonAlias({"dbConnectionId", "connId"})
    private Long connectionId;
    private String schema;       // For SQL schemas
    private String database;     // For MongoDB databases
    private String dirPath;      // For JSON local or COS directory
    private Long cosId;          // Optional COS connection ID for JSON
    private String storageType;  // "LOCAL" or "COS"
    private String bucketName;   // For COS buckets

    private String jobName;
    private String department;

    private List<BulkDumpTableConfig> tables;

    // Optional direct connection overrides
    private String url;
    private String username;
    private String password;
}
