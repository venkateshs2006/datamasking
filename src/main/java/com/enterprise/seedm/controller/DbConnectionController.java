package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.service.DbConnectionService;
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

@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
@Slf4j
public class DbConnectionController {

    private final DbConnectionService connectionService;

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
                    allowedDepartments.addAll((List<String>) deptsObj);
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
        if (session == null || !"ADMIN".equals(session.getAttribute("role"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only Admins can add connections"));
        }

        DbConnection saved = connectionService.saveConnection(connection);
        return ResponseEntity.ok(saved);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteConnection(@PathVariable Long id, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !"ADMIN".equals(session.getAttribute("role"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only Admins can delete connections"));
        }
        
        connectionService.deleteConnection(id);
        return ResponseEntity.ok(Map.of("status", "SUCCESS"));
    }
}
