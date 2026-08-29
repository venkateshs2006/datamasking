package com.enterprise.seedm.service;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.model.Department;
import com.enterprise.seedm.model.Role;
import com.enterprise.seedm.repository.DepartmentRepository;
import com.enterprise.seedm.repository.RoleRepository;
import com.enterprise.seedm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;

    public List<AppUser> getAllUsers() {
        return userRepository.findAll();
    }

    public AppUser getUser(Long id) {
        return userRepository.findById(id).orElse(null);
    }

    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    public AppUser createUser(String username, String password, String roleName, Set<String> departmentNames) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        if (userRepository.findByUsername(username.trim()) != null) {
            throw new IllegalArgumentException("Username already exists: " + username.trim());
        }

        Role role = resolveRole(roleName);
        Set<Department> departments = resolveDepartments(departmentNames);

        AppUser newUser = new AppUser();
        newUser.setUsername(username.trim());
        newUser.setPassword(passwordEncoder.encode(password.trim()));
        newUser.setRole(role);
        newUser.setDepartments(departments);

        AppUser saved = userRepository.save(newUser);
        log.info("Created user {} with role {}", saved.getUsername(), role != null ? role.getName() : "NONE");
        return saved;
    }

    public AppUser updateUser(Long userId, String username, String password, String roleName, Set<String> departmentNames) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));

        if (username != null && !username.trim().isEmpty()) {
            String trimmedUsername = username.trim();
            AppUser existing = userRepository.findByUsername(trimmedUsername);
            if (existing != null && !existing.getId().equals(userId)) {
                throw new IllegalArgumentException("Username already taken: " + trimmedUsername);
            }
            user.setUsername(trimmedUsername);
        }

        if (password != null && !password.trim().isEmpty()) {
            user.setPassword(passwordEncoder.encode(password.trim()));
        }

        if (roleName != null && !roleName.trim().isEmpty()) {
            user.setRole(resolveRole(roleName));
        }

        if (departmentNames != null) {
            user.setDepartments(resolveDepartments(departmentNames));
        }

        AppUser updated = userRepository.save(user);
        log.info("Updated user id {} ({})", updated.getId(), updated.getUsername());
        return updated;
    }

    public void deleteUser(Long userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
        userRepository.delete(user);
        log.info("Deleted user {} (id: {})", user.getUsername(), userId);
    }

    private Role resolveRole(String roleName) {
        if (roleName == null || roleName.trim().isEmpty()) {
            return null;
        }
        String cleanRole = roleName.trim();
        return roleRepository.findByNameIgnoreCase(cleanRole)
                .or(() -> roleRepository.findByName(cleanRole))
                .orElseGet(() -> {
                    Role newRole = new Role(null, cleanRole.toLowerCase());
                    return roleRepository.save(newRole);
                });
    }

    private Set<Department> resolveDepartments(Set<String> departmentNames) {
        if (departmentNames == null || departmentNames.isEmpty()) {
            return new HashSet<>();
        }
        Set<Department> departments = new HashSet<>();
        for (String deptName : departmentNames) {
            if (deptName == null || deptName.trim().isEmpty()) continue;
            String clean = deptName.trim();
            Department dept = departmentRepository.findByNameIgnoreCase(clean);
            if (dept == null) {
                dept = departmentRepository.findByName(clean);
            }
            if (dept == null) {
                dept = departmentRepository.save(new Department(null, clean));
            }
            departments.add(dept);
        }
        return departments;
    }
}