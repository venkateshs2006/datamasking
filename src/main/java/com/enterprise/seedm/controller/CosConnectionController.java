package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.Department;
import com.enterprise.seedm.service.CosConnectionService;
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
@RequestMapping("/api/cos-connections")
@RequiredArgsConstructor
@Slf4j
public class CosConnectionController {

    private final CosConnectionService connectionService;

    @GetMapping
    public ResponseEntity<?> getConnections(
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String envType,
            @RequestParam(required = false) String storageType,
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

            List<CosConnection> connections = connectionService.getConnectionsByFilters(allowedDepartments, envType, storageType);
            
            // Scrub sensitive credentials before sending to frontend
            connections.forEach(c -> {
                c.setApiKey("********");
                c.setSecretKey("********");
            });
            
            return ResponseEntity.ok(connections);
        } catch (Exception e) {
            log.error("Failed to fetch COS connections", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Internal error fetching connections: " + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> addConnection(@RequestBody CosConnection connection, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String role = (String) session.getAttribute("role");
        String user = (String) session.getAttribute("user");
        connection.setCreatedBy(user);

        if ("ADMIN".equals(role)) {
            CosConnection saved = connectionService.saveConnection(connection);
            return ResponseEntity.ok(saved);
        }

        if ("MANAGER".equals(role)) {
            List<Department> departments = (List<Department>) session.getAttribute("departments");
            if (departments != null && departments.stream().anyMatch(d -> d.getName().equals(connection.getDepartment()))) {
                CosConnection saved = connectionService.saveConnection(connection);
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
            CosConnection connection = connectionService.getConnection(id);
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
}