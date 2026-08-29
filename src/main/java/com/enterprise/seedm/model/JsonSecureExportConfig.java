package com.enterprise.seedm.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class JsonSecureExportConfig {
    private String jobName;
    private StorageConfig source;
    private StorageConfig dest;
    private RulesConfig rules;

    @Data
    public static class StorageConfig {
        private String type; // "local" or "cos"
        private Long cosId;
        private String path; // Local directory path or COS prefix
        private String bucketName;
    }

    @Data
    public static class RulesConfig {
        private List<String> targetFiles; // List of json files selected
        private Map<String, List<String>> maskingFields; // file -> list of field paths
        private String maskingKey; // Salt key for FPE and AES-256 encryption
    }
}
