package com.enterprise.seedm.dto;

import java.util.List;
import java.util.Map;

public class SecureExportRequest {
    private Long sourceConnectionId;
    private String schema;
    private String storageType;
    private Long cosConnectionId;
    private String localPath;
    private List<String> tables;
    private Map<String, Object> rules;

    // Getters and Setters
    public Long getSourceConnectionId() {
        return sourceConnectionId;
    }

    public void setSourceConnectionId(Long sourceConnectionId) {
        this.sourceConnectionId = sourceConnectionId;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public Long getCosConnectionId() {
        return cosConnectionId;
    }

    public void setCosConnectionId(Long cosConnectionId) {
        this.cosConnectionId = cosConnectionId;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public List<String> getTables() {
        return tables;
    }

    public void setTables(List<String> tables) {
        this.tables = tables;
    }

    public Map<String, Object> getRules() {
        return rules;
    }

    public void setRules(Map<String, Object> rules) {
        this.rules = rules;
    }
}
