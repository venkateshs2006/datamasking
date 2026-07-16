package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping
    public ResponseEntity<AppUser> createUser(@RequestBody UserRequest userRequest) {
        AppUser newUser = userService.createUser(userRequest.getUsername(), userRequest.getPassword(), userRequest.getRole(), userRequest.getDepartments());
        return ResponseEntity.ok(newUser);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AppUser> updateUser(@PathVariable Long id, @RequestBody UserRequest userRequest) {
        AppUser updatedUser = userService.updateUser(id, userRequest.getUsername(), userRequest.getRole(), userRequest.getDepartments());
        return ResponseEntity.ok(updatedUser);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // DTO for user requests
    static class UserRequest {
        private String username;
        private String password;
        private String role;
        private Set<String> departments;

        // Getters and setters
        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public Set<String> getDepartments() {
            return departments;
        }

        public void setDepartments(Set<String> departments) {
            this.departments = departments;
        }
    }
}