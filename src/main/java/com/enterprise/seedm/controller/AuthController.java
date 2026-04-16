package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();
        String role = null;

        if ("scheduler".equals(username) && "pass123".equals(password)) {
            role = "SCHEDULER";
        } else if ("approver".equals(username) && "pass123".equals(password)) {
            role = "APPROVER";
        } else if ("admin".equals(username) && "admin123".equals(password)) {
            role = "ADMIN";
        }

        if (role != null) {
            HttpSession session = request.getSession(true);
            session.setAttribute("user", username);
            session.setAttribute("role", role);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "redirect", "/select-db.html", "role", role));
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "FAILED", "message", "Invalid credentials. Try scheduler/pass123 or approver/pass123"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("status", "SUCCESS"));
    }

    @GetMapping("/check")
    public ResponseEntity<?> checkAuth(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("user") != null) {
            return ResponseEntity.ok(Map.of("authenticated", true, "user", session.getAttribute("user"), "role", session.getAttribute("role")));
        }
        return ResponseEntity.ok(Map.of("authenticated", false));
    }
}
