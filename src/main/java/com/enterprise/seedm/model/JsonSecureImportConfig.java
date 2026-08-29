package com.enterprise.seedm.model;

import lombok.Data;

@Data
public class JsonSecureImportConfig {
    private String jobName;
    private StorageConfig storage;
    private DestinationConfig dest;
    private String secretKey;

    @Data
    public static class StorageConfig {
        private String type; // "local" or "cos"
        private Long id;     // COS connection ID if applicable
        private String name; // Connection name
        private String path; // Directory path or file path
        private String fileName; // e.g. secure-json-export.json.enc
    }

    @Data
    public static class DestinationConfig {
        private String type; // "local" or "cos"
        private Long cosId;
        private String path; // Target directory path
        private boolean overwrite; // Overwrite existing json files
    }
}
