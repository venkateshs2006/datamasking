package com.enterprise.seedm.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Job Configuration Model
 * Maps to the YAML configuration structure
 */
@Data
public class JobConfig {
    private JobProfile jobProfile;
    private Connections connections;
    private List<VirtualRelationship> virtualRelationships;
    private List<MaskingRule> maskingRules;

    @Data
    public static class JobProfile {
        private String name;
        private String batchMode;
        private Integer chunkSize;
        private Integer threads;
    }

    @Data
    public static class Connections {
        private DatabaseConnection source;
        private DatabaseConnection target;
    }

    @Data
    public static class DatabaseConnection {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        private Integer maxPoolSize;
        private Integer minIdle;
    }

    @Data
    public static class VirtualRelationship {
        private String parent;
        private String child;
    }

    @Data
    public static class MaskingRule {
        private String table;
        private List<ColumnMaskingRule> columns;
    }

    @Data
    public static class ColumnMaskingRule {
        private String name;
        private String action;
        private String keyRef;
        private Integer variancePercent;
        private Map<String, Object> parameters;
    }
}