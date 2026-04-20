package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.service.DbConnectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
public class DbConnectionController {

    private final DbConnectionService connectionService;

    @GetMapping
    public ResponseEntity<?> getConnections(
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String dbType,
            @RequestParam(required = false) String envType,
            HttpServletRequest request) {
        
        HttpSession session = request.getSession(false);
        
        List<String> allowedDepartments = new ArrayList<>();
        
        // If an explicit department is requested (e.g. by ADMIN on the admin-db page), check if they are ADMIN
        if (department != null && !department.isEmpty()) {
            if (session == null || !"ADMIN".equals(session.getAttribute("role"))) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only Admins can filter by department explicitly"));
            }
            allowedDepartments.add(department);
        } else {
            // Otherwise, infer from session
            if (session == null || session.getAttribute("departments") == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            allowedDepartments = (List<String>) session.getAttribute("departments");
        }

        List<DbConnection> connections = connectionService.getConnectionsByFilters(allowedDepartments, dbType, envType);
        
        // Scrub passwords before sending to frontend
        connections.forEach(c -> c.setPassword("********"));
        
        return ResponseEntity.ok(connections);
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
    public ResponseEntity<?> deleteConnection(@PathVariable String id, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !"ADMIN".equals(session.getAttribute("role"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only Admins can delete connections"));
        }
        
        connectionService.deleteConnection(id);
        return ResponseEntity.ok(Map.of("status", "SUCCESS"));
    }
}
