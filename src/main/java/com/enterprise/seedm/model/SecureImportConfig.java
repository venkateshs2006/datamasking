package com.enterprise.seedm.model;

import lombok.Data;

@Data
public class SecureImportConfig {
    private String jobName;
    private StorageConfig storage;
    private DestinationConfig dest;
    private String secretKey;

    @Data
    public static class StorageConfig {
        private String type; // "local" or "cos"
        private Long id;     // COS connection ID if applicable
        private String name; // Connection name or bucket name
        private String path; // Directory path or file path
        private String fileName;
    }

    @Data
    public static class DestinationConfig {
        private Long id;     // DbConnection ID
        private String url;
        private String username;
        private String password;
        private String schema;
    }
}
