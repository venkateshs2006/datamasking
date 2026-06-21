package com.enterprise.seedm.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "db_connections")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbConnection {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_name", nullable = false)
    private String name;

    @Column(name = "department", nullable = false)
    private String department; // "Finance", "HR", "IT", "Admin", etc.

    @Column(name = "type_of_database", nullable = false)
    private String dbType; // "postgres", "mongo", "json"

    @Column(name = "environment", nullable = false)
    private String envType; // "source", "destination"

    @Column(name = "dburi")
    private String url; // For json this is the directory path

    @Column(name = "username")
    private String username;

    @Column(name = "password")
    private String password;
    
    @Column(name = "vault_path")
    private String vaultPath; // Path in HashiCorp Vault to fetch url, username, and password
    
    @Column(name = "vault_role")
    private String vaultRole;
}