package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.model.Role;
import com.enterprise.seedm.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    private boolean checkAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("user") == null) {
            return false;
        }
        String role = (String) session.getAttribute("role");
        return role != null && "ADMIN".equalsIgnoreCase(role);
    }

    @GetMapping
    public ResponseEntity<?> getAllUsers(HttpServletRequest request) {
        if (!checkAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied. Only Admins can manage users."));
        }
        List<AppUser> users = userService.getAllUsers();
        users.forEach(u -> u.setPassword(null));
        return ResponseEntity.ok(users);
    }

    @GetMapping("/roles")
    public ResponseEntity<?> getAllRoles(HttpServletRequest request) {
        List<Role> roles = userService.getAllRoles();
        return ResponseEntity.ok(roles);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUser(@PathVariable Long id, HttpServletRequest request) {
        if (!checkAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied. Only Admins can manage users."));
        }
        AppUser user = userService.getUser(id);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        user.setPassword(null);
        return ResponseEntity.ok(user);
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody UserRequest userRequest, HttpServletRequest request) {
        if (!checkAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied. Only Admins can manage users."));
        }
        try {
            AppUser newUser = userService.createUser(
                    userRequest.getUsername(),
                    userRequest.getPassword(),
                    userRequest.getRole(),
                    userRequest.getDepartments()
            );
            newUser.setPassword(null);
            return ResponseEntity.ok(newUser);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to create user", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error creating user: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody UserRequest userRequest, HttpServletRequest request) {
        if (!checkAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied. Only Admins can manage users."));
        }
        try {
            AppUser updatedUser = userService.updateUser(
                    id,
                    userRequest.getUsername(),
                    userRequest.getPassword(),
                    userRequest.getRole(),
                    userRequest.getDepartments()
            );
            updatedUser.setPassword(null);
            return ResponseEntity.ok(updatedUser);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update user id {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error updating user: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, HttpServletRequest request) {
        if (!checkAdmin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied. Only Admins can manage users."));
        }
        try {
            HttpSession session = request.getSession(false);
            String currentUsername = session != null ? (String) session.getAttribute("user") : null;
            AppUser target = userService.getUser(id);
            if (target != null && currentUsername != null && currentUsername.equalsIgnoreCase(target.getUsername())) {
                return ResponseEntity.badRequest().body(Map.of("message", "Cannot delete your own currently logged-in admin account."));
            }

            userService.deleteUser(id);
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "User deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete user id {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error deleting user: " + e.getMessage()));
        }
    }

    @Data
    public static class UserRequest {
        private String username;
        private String password;
        private String role;
        private Set<String> departments;
    }
}