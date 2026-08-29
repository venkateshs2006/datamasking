package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.model.Department;
import com.enterprise.seedm.model.LoginRequest;
import com.enterprise.seedm.repository.UserRepository;
import com.enterprise.seedm.service.AuthService;
import com.enterprise.seedm.service.DepartmentService;
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
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final DepartmentService departmentService;
    private final UserRepository userRepository;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();

        AppUser user = authService.authenticate(username, password);

        if (user != null) {
            HttpSession session = request.getSession(true);
            
            String primaryRole = user.getRole() == null ? "VIEWER" : user.getRole().getName();
            boolean isAdmin = "ADMIN".equalsIgnoreCase(primaryRole);

            List<Department> accessibleDepartments;
            String primaryDepartment;

            if (isAdmin) {
                accessibleDepartments = departmentService.getAllDepartments();
                primaryDepartment = "ALL";
            } else {
                accessibleDepartments = new ArrayList<>(user.getDepartments());
                primaryDepartment = accessibleDepartments.isEmpty() ? "NONE" : accessibleDepartments.get(0).getName();
            }
            
            session.setAttribute("user", user.getUsername());
            session.setAttribute("role", primaryRole.toUpperCase());
            session.setAttribute("roles", user.getRole());
            session.setAttribute("department", primaryDepartment);
            session.setAttribute("departments", accessibleDepartments);
            
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS", 
                    "redirect", "/select-db.html", 
                    "role", primaryRole.toUpperCase(),
                    "department", primaryDepartment,
                    "roles", user.getRole(),
                    "departments", accessibleDepartments
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
            String username = (String) session.getAttribute("user");
            String role = (String) session.getAttribute("role");
            boolean isAdmin = role != null && "ADMIN".equalsIgnoreCase(role);

            List<Department> accessibleDepartments;
            String primaryDepartment;

            if (isAdmin) {
                accessibleDepartments = departmentService.getAllDepartments();
                primaryDepartment = "ALL";
                session.setAttribute("departments", accessibleDepartments);
                session.setAttribute("department", primaryDepartment);
            } else {
                AppUser user = userRepository.findByUsername(username);
                if (user != null) {
                    accessibleDepartments = new ArrayList<>(user.getDepartments());
                    primaryDepartment = accessibleDepartments.isEmpty() ? "NONE" : accessibleDepartments.get(0).getName();
                    session.setAttribute("departments", accessibleDepartments);
                    session.setAttribute("department", primaryDepartment);
                } else {
                    accessibleDepartments = (List<Department>) session.getAttribute("departments");
                    primaryDepartment = (String) session.getAttribute("department");
                }
            }

            return ResponseEntity.ok(Map.of(
                    "authenticated", true, 
                    "user", username, 
                    "role", role != null ? role.toUpperCase() : "VIEWER",
                    "roles", session.getAttribute("roles") != null ? session.getAttribute("roles") : new ArrayList<>(),
                    "department", primaryDepartment != null ? primaryDepartment : "NONE",
                    "departments", accessibleDepartments != null ? accessibleDepartments : new ArrayList<>()
            ));
        }
        return ResponseEntity.ok(Map.of("authenticated", false));
    }
}
