package com.enterprise.seedm.model;

import lombok.Data;

@Data
public class MongoSecureImportConfig {
    private String jobName;
    private StorageConfig storage;
    private DestinationConfig dest;
    private String secretKey;

    @Data
    public static class StorageConfig {
        private String type; // "local" or "cos"
        private Long id;     // COS connection ID if applicable
        private Long cosId;  // Alias for id
        private String name; // Connection name or bucket name
        private String path; // Directory path or file path
        private String fileName;

        public Long getCosId() {
            return cosId != null ? cosId : id;
        }
    }

    @Data
    public static class DestinationConfig {
        private Long id; // Mongo Connection ID
        private String url;
        private String username;
        private String password;
        private String database;
        private boolean dropExisting; // Drop existing collections before import
    }
}
