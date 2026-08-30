package com.enterprise.seedm.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class JsonSecureExportConfig {
    private String jobName;
    private String department;
    private StorageConfig source;
    private StorageConfig dest;
    private StorageConfig storage;
    private RulesConfig rules;

    @Data
    public static class StorageConfig {
        private String type; // "local" or "cos"
        private Long id;
        private Long cosId;
        private String name;
        private String path; // Local directory path or COS prefix
        private String bucketName;
        private String destDir;
    }

    @Data
    public static class RulesConfig {
        private List<String> targetFiles = new ArrayList<>();
        private List<String> targetTables = new ArrayList<>();
        private List<String> targetCollections = new ArrayList<>();
        private List<String> maskingColumns = new ArrayList<>();
        private List<String> partialMaskingColumns = new ArrayList<>();
        private List<String> constraintColumns = new ArrayList<>();
        private List<String> constraintFields = new ArrayList<>();
        private Map<String, List<String>> maskingFields; // file -> list of field paths (SFD)
        private Map<String, List<String>> partialMaskingFields; // file -> list of field paths (PMD)
        private String maskingKey; // Salt key for FPE and AES-256 encryption
    }
}
