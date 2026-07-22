package com.enterprise.seedm.dto;

public class SecureExportRequest {
    private Long sourceConnectionId;
    private String storageType;
    private Long cosConnectionId;
    private String localPath;

    // Getters and Setters
    public Long getSourceConnectionId() {
        return sourceConnectionId;
    }

    public void setSourceConnectionId(Long sourceConnectionId) {
        this.sourceConnectionId = sourceConnectionId;
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
}
