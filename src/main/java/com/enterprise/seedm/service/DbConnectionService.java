package com.enterprise.seedm.service;

import com.enterprise.seedm.model.DbConnection;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DbConnectionService {
    private final Map<String, DbConnection> connections = new ConcurrentHashMap<>();

    public DbConnectionService() {
        // Pre-populate some dummy connections for testing
        saveConnection(new DbConnection(null, "Finance Prod DB", "Finance", "postgres", "source", "jdbc:postgresql://localhost:5432/finance_prod", "fin_user", "fin_pass"));
        saveConnection(new DbConnection(null, "Finance QA DB", "Finance", "postgres", "destination", "jdbc:postgresql://localhost:5432/finance_qa", "qa_user", "qa_pass"));
        
        saveConnection(new DbConnection(null, "HR Prod Mongo", "HR", "mongo", "source", "mongodb://localhost:27017/hr_prod", "hr_user", "hr_pass"));
        saveConnection(new DbConnection(null, "HR Test Mongo", "HR", "mongo", "destination", "mongodb://localhost:27017/hr_test", "hr_test", "hr_test"));
        
        saveConnection(new DbConnection(null, "IT Logs Dir", "IT", "json", "source", "/var/logs/it/prod", "", ""));
        saveConnection(new DbConnection(null, "IT Masked Logs Dir", "IT", "json", "destination", "/var/logs/it/masked", "", ""));

        saveConnection(new DbConnection(null, "Admin Master DB", "Admin", "postgres", "source", "jdbc:postgresql://localhost:5432/admin_master", "admin_db", "admin_db"));
        saveConnection(new DbConnection(null, "Admin Masked DB", "Admin", "postgres", "destination", "jdbc:postgresql://localhost:5432/admin_masked", "admin_db", "admin_db"));
    }

    public DbConnection saveConnection(DbConnection connection) {
        if (connection.getId() == null) {
            connection.setId(UUID.randomUUID().toString());
        }
        connections.put(connection.getId(), connection);
        return connection;
    }

    public List<DbConnection> getAllConnections() {
        return new ArrayList<>(connections.values());
    }

    public List<DbConnection> getConnectionsByFilters(List<String> departments, String dbType, String envType) {
        return connections.values().stream()
                .filter(c -> departments.contains("ALL") || departments.contains(c.getDepartment()))
                .filter(c -> dbType == null || dbType.isEmpty() || c.getDbType().equalsIgnoreCase(dbType))
                .filter(c -> envType == null || envType.isEmpty() || c.getEnvType().equalsIgnoreCase(envType))
                .collect(Collectors.toList());
    }

    public DbConnection getConnection(String id) {
        return connections.get(id);
    }
    
    public void deleteConnection(String id) {
        connections.remove(id);
    }
}
