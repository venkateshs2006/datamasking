package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.repository.CosConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CosConnectionService {
    private final CosConnectionRepository cosConnectionRepository;

    public CosConnection saveConnection(CosConnection connection) {
        connection.setCreatedAt(System.currentTimeMillis());
        return cosConnectionRepository.save(connection);
    }

    public List<CosConnection> getAllConnections() {
        return cosConnectionRepository.findAll();
    }

    public List<CosConnection> getConnectionsByFilters(List<String> departments, String envType) {
        return cosConnectionRepository.findAll().stream()
                .filter(c -> departments != null && (departments.contains("ALL") || departments.contains(c.getDepartment())))
                .filter(c -> envType == null || envType.isEmpty() || c.getEnvType().equalsIgnoreCase(envType))
                .collect(Collectors.toList());
    }

    public CosConnection getConnection(Long id) {
        return cosConnectionRepository.findById(id).orElse(null);
    }

    public void deleteConnection(Long id) {
        cosConnectionRepository.deleteById(id);
    }
}
