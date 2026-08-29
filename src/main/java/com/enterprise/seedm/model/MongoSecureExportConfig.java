package com.enterprise.seedm.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class MongoSecureExportConfig {
    private String jobName;
    private SourceConfig source;
    private DestinationConfig dest;
    private RulesConfig rules;

    @Data
    public static class SourceConfig {
        private Long id; // Connection ID
        private String url;
        private String username;
        private String password;
        private String database;
        private String schema;
    }

    @Data
    public static class DestinationConfig {
        private String type; // "local" or "cos"
        private String destDir;
        private Long cosId;
        private String bucketName;
        private String path;
    }

    @Data
    public static class RulesConfig {
        private List<String> targetCollections;
        private List<String> targetTables;
        private Map<String, List<String>> maskingFields; // collection -> list of fields
        private Map<String, List<String>> partialMaskingFields;
        private List<String> maskingColumns; // collection.field (SFD)
        private List<String> partialMaskingColumns; // collection.field (PMD)
        private List<String> constraintColumns; // collection.field (FPH)
        private String maskingKey;
    }
}
