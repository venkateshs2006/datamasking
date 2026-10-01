package com.enterprise.seedm.service;

import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.repository.DbConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DbConnectionService {
    private final DbConnectionRepository connectionRepository;

    public DbConnection saveConnection(DbConnection connection) {
        return connectionRepository.save(connection);
    }

    public List<DbConnection> getAllConnections() {
        return connectionRepository.findAll();
    }

    public List<DbConnection> getConnectionsByFilters(List<String> departments, String dbType, String envType) {
        return connectionRepository.findAll().stream()
                .filter(c -> departments != null && (departments.contains("ALL") || 
                        (c.getDepartment() != null && departments.stream().anyMatch(d -> d.equalsIgnoreCase(c.getDepartment())))))
                .filter(c -> dbType == null || dbType.isEmpty() || 
                        (c.getDbType() != null && (c.getDbType().equalsIgnoreCase(dbType) || 
                         (dbType.toLowerCase().contains("mongo") && c.getDbType().toLowerCase().contains("mongo")))))
                .filter(c -> envType == null || envType.isEmpty() || 
                        (c.getEnvType() != null && c.getEnvType().equalsIgnoreCase(envType)))
                .collect(Collectors.toList());
    }

    public DbConnection getConnection(Long id) {
        return connectionRepository.findById(id).orElse(null);
    }
    
    public void deleteConnection(Long id) {
        connectionRepository.deleteById(id);
    }
}
