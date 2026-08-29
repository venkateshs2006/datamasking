package com.enterprise.seedm.service;

import com.enterprise.seedm.model.DbConnection;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.UuidRepresentation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class MongoConnectionHelper {

    private final DbConnectionService dbConnectionService;
    private final VaultService vaultService;

    public MongoClient createClient(Long connectionId) {
        DbConnection connection = dbConnectionService.getConnection(connectionId);
        if (connection == null) {
            throw new IllegalArgumentException("Database connection ID not found: " + connectionId);
        }
        return createClient(connection);
    }

    public MongoClient createClient(DbConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("DbConnection cannot be null");
        }

        String url = connection.getUrl();
        String username = connection.getUsername();
        String password = connection.getPassword();

        // Resolve credentials from HashiCorp Vault if configured
        if (StringUtils.hasText(connection.getVaultPath())) {
            try {
                String path = connection.getVaultPath();
                if (StringUtils.hasText(connection.getVaultRole())) {
                    path = path + "/" + connection.getVaultRole();
                }
                Map<String, Object> creds = vaultService.getDatabaseCredentials(path);
                if (creds != null) {
                    if (creds.containsKey("url")) url = (String) creds.get("url");
                    if (creds.containsKey("username")) username = (String) creds.get("username");
                    if (creds.containsKey("password")) password = (String) creds.get("password");
                    if (creds.containsKey("data") && creds.get("data") instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> nested = (Map<String, Object>) creds.get("data");
                        if (nested.containsKey("url")) url = (String) nested.get("url");
                        if (nested.containsKey("username")) username = (String) nested.get("username");
                        if (nested.containsKey("password")) password = (String) nested.get("password");
                    }
                }
            } catch (Exception e) {
                log.warn("Could not retrieve Vault credentials for connection {}: {}", connection.getId(), e.getMessage());
            }
        }

        return createClient(url, username, password);
    }

    public MongoClient createClient(String url, String username, String password) {
        if (url == null || url.trim().isEmpty()) {
            url = "mongodb://localhost:27017";
        }

        ConnectionString connectionString = new ConnectionString(url);
        MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .uuidRepresentation(UuidRepresentation.STANDARD);

        // If credentials are not already present in the connection string URI
        if (connectionString.getUsername() == null && StringUtils.hasText(username)) {
            String authSource = "admin";
            if (connectionString.getDatabase() != null && !connectionString.getDatabase().isEmpty()) {
                authSource = connectionString.getDatabase();
            }
            MongoCredential credential = MongoCredential.createCredential(
                    username.trim(),
                    authSource,
                    password != null ? password.toCharArray() : new char[0]
            );
            settingsBuilder.credential(credential);
        }

        return MongoClients.create(settingsBuilder.build());
    }
}
