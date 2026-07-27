package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.model.DbConnectionRequest;
import com.enterprise.seedm.model.Department;
import com.enterprise.seedm.service.DbConnectionService;
import com.enterprise.seedm.service.DynamicDataSourceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
@Slf4j
public class DbConnectionController {

    private final DbConnectionService connectionService;
    private final DynamicDataSourceService dynamicDataSourceService;

    private void resolveConnection(DbConnectionRequest request) {
        if (request.getId() != null) {
            DbConnection saved = connectionService.getConnection(request.getId());
            if (saved != null) {
                request.setUrl(saved.getUrl());
                request.setUsername(saved.getUsername());
                request.setPassword(saved.getPassword());
            } else {
                throw new IllegalArgumentException("Connection ID not found: " + request.getId());
            }
        }
    }

    @GetMapping
    public ResponseEntity<?> getConnections(
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String dbType,
            @RequestParam(required = false) String envType,
            HttpServletRequest request) {
        
        try {
            HttpSession session = request.getSession(false);
            
            List<String> allowedDepartments = new ArrayList<>();
            
            if (department != null && !department.isEmpty()) {
                if (session == null || !"ADMIN".equals(session.getAttribute("role"))) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only Admins can filter by department explicitly"));
                }
                allowedDepartments.add(department);
            } else {
                if (session == null) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }
                
                Object deptsObj = session.getAttribute("departments");
                if (deptsObj instanceof List) {
                    List<?> deptsList = (List<?>) deptsObj;
                    if (!deptsList.isEmpty() && deptsList.get(0) instanceof Department) {
                        allowedDepartments.addAll(deptsList.stream().map(d -> ((Department) d).getName()).collect(Collectors.toList()));
                    } else if (!deptsList.isEmpty() && deptsList.get(0) instanceof String) {
                        allowedDepartments.addAll((List<String>) deptsList);
                    }
                }
                
                if (allowedDepartments.isEmpty() && session.getAttribute("department") != null) {
                    allowedDepartments.add((String) session.getAttribute("department"));
                }
                
                if (allowedDepartments.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }
            }

            List<DbConnection> connections = connectionService.getConnectionsByFilters(allowedDepartments, dbType, envType);
            
            // Scrub passwords before sending to frontend
            connections.forEach(c -> c.setPassword("********"));
            
            return ResponseEntity.ok(connections);
        } catch (Exception e) {
            log.error("Failed to fetch DB connections", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Internal error fetching connections: " + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> addConnection(@RequestBody DbConnection connection, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String role = (String) session.getAttribute("role");
        if ("ADMIN".equals(role)) {
            DbConnection saved = connectionService.saveConnection(connection);
            return ResponseEntity.ok(saved);
        }

        if ("MANAGER".equals(role)) {
            List<Department> departments = (List<Department>) session.getAttribute("departments");
            if (departments != null && departments.stream().anyMatch(d -> d.getName().equals(connection.getDepartment()))) {
                DbConnection saved = connectionService.saveConnection(connection);
                return ResponseEntity.ok(saved);
            } else {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "You are not authorized to add a connection for this department."));
            }
        }

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "You are not authorized to perform this action."));
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteConnection(@PathVariable Long id, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String role = (String) session.getAttribute("role");
        if ("ADMIN".equals(role)) {
            connectionService.deleteConnection(id);
            return ResponseEntity.ok(Map.of("status", "SUCCESS"));
        }

        if ("MANAGER".equals(role)) {
            DbConnection connection = connectionService.getConnection(id);
            if (connection != null) {
                List<Department> departments = (List<Department>) session.getAttribute("departments");
                if (departments != null && departments.stream().anyMatch(d -> d.getName().equals(connection.getDepartment()))) {
                    connectionService.deleteConnection(id);
                    return ResponseEntity.ok(Map.of("status", "SUCCESS"));
                }
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "You are not authorized to delete this connection."));
        }

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "You are not authorized to perform this action."));
    }

    @PostMapping("/schemas")
    public List<String> getSchemasLegacy(@RequestBody DbConnectionRequest request) {
        resolveConnection(request);
        log.info("Fetching schemas for {} connection", request.getType());
        return dynamicDataSourceService.fetchSchemas(request);
    }

    @GetMapping("/{id}/schemas")
    public List<String> getSchemas(@PathVariable Long id) {
        DbConnectionRequest request = new DbConnectionRequest();
        request.setId(id);
        resolveConnection(request);
        log.info("Fetching schemas for {} connection", request.getType());
        return dynamicDataSourceService.fetchSchemas(request);
    }

    @PostMapping("/create-schema")
    public ResponseEntity<?> createSchema(@RequestBody DbConnectionRequest request) {
        try {
            resolveConnection(request);
            dynamicDataSourceService.createSchema(request);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Schema '" + request.getSchema() + "' created successfully."));
        } catch (Exception e) {
            log.error("Failed to create schema", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    @PostMapping("/update")
    public Map<String, String> updateConnection(@RequestBody DbConnectionRequest request) {
        resolveConnection(request);
        log.info("Updating {} connection", request.getType());
        dynamicDataSourceService.updateConnection(request);
        return Map.of("status", "SUCCESS", "message", request.getType() + " connection updated successfully");
    }
}