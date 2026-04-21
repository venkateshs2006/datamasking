package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.model.LoginRequest;
import com.enterprise.seedm.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();

        AppUser user = authService.authenticate(username, password);

        if (user != null) {
            HttpSession session = request.getSession(true);
            
            // Just picking the first role/department for session context if they have multiple, 
            // or we could store the full lists in session.
            // For existing UI which expects a single role/department string, we'll store the primary one
            // but also provide the lists if needed.
            String primaryRole = user.getRoles().isEmpty() ? "VIEWER" : user.getRoles().iterator().next();
            String primaryDepartment = user.getDepartments().isEmpty() ? "NONE" : user.getDepartments().iterator().next();
            
            session.setAttribute("user", user.getUsername());
            session.setAttribute("role", primaryRole);
            session.setAttribute("roles", new ArrayList<>(user.getRoles()));
            session.setAttribute("department", primaryDepartment);
            session.setAttribute("departments", new ArrayList<>(user.getDepartments()));
            
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS", 
                    "redirect", "/select-db.html", 
                    "role", primaryRole, 
                    "department", primaryDepartment,
                    "roles", user.getRoles(),
                    "departments", user.getDepartments()
            ));
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "FAILED", "message", "Invalid credentials"));
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
            return ResponseEntity.ok(Map.of(
                    "authenticated", true, 
                    "user", session.getAttribute("user"), 
                    "role", session.getAttribute("role"),
                    "roles", session.getAttribute("roles") != null ? session.getAttribute("roles") : new ArrayList<>(),
                    "department", session.getAttribute("department"),
                    "departments", session.getAttribute("departments") != null ? session.getAttribute("departments") : new ArrayList<>()
            ));
        }
        return ResponseEntity.ok(Map.of("authenticated", false));
    }
}
