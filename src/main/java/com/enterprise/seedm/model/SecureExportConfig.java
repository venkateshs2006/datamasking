package com.enterprise.seedm.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SecureExportConfig {
    private String jobName;
    private SourceConfig source;
    private DestinationConfig dest;
    private StorageConfig storage;
    private RulesConfig rules;

    @Data
    public static class SourceConfig {
        private String url;
        private String username;
        private String password;
        private String schema;
        private String driverClassName;
    }

    @Data
    public static class DestinationConfig {
        private String type; // "local" or "cos"
        private Long id;
        private Long cosId;
        private String destDir;
        private String path;
        private String bucketName;
    }

    @Data
    public static class StorageConfig {
        private String type;
        private Long id;
        private Long cosId;
        private String name;
        private String path;
    }

    @Data
    public static class RulesConfig {
        private List<String> maskingColumns;
        private List<String> partialMaskingColumns;
        private List<String> constraintFields;
        private List<String> constraintColumns;
        private List<String> targetTables;
        private String maskingKey;
    }
}