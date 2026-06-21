package com.enterprise.seedm.model;

import lombok.Data;

@Data
public class DbConnectionRequest {
    private Long id; // Optional, references a saved DbConnection
    private String type; // "source" or "destination"
    private String url;
    private String username;
    private String password;
    private String schema;
    private String vaultPath;
    private String vaultRole;
}