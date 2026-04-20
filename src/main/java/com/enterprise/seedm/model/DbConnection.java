package com.enterprise.seedm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbConnection {
    private String id;
    private String name;
    private String department; // "Finance", "HR", "IT", "Admin"
    private String dbType; // "postgres", "mongo", "json"
    private String envType; // "source", "destination"
    private String url; // For json this is the directory path
    private String username;
    private String password;
}
