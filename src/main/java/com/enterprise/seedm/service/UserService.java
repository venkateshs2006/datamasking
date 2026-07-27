package com.enterprise.seedm.service;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.model.Department;
import com.enterprise.seedm.model.Role;
import com.enterprise.seedm.repository.UserRepository;
import com.enterprise.seedm.repository.RoleRepository;
import com.enterprise.seedm.repository.DepartmentRepository;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public AppUser createUser(String username, String password, String roleName, Set<String> departmentNames) {


        Role role = roleRepository.findByName(roleName).orElseThrow(() -> new RuntimeException("Role not found: " + roleName));
        Set<Department> departments = departmentNames.stream()
                .map(name -> Optional.ofNullable(departmentRepository.findByName(name))
                        .orElseThrow(() -> new RuntimeException("Department not found: " + name)))
                .collect(Collectors.toSet());

        AppUser newUser = new AppUser();
        newUser.setUsername(username);
        newUser.setPassword(passwordEncoder.encode(password));
        newUser.setRole(role);
        newUser.setDepartments(departments);

        return userRepository.save(newUser);
    }

    public AppUser updateUser(Long userId, String username, String roleName, Set<String> departmentNames) {
        if (!isAuthorized()) {
            throw new SecurityException("User not authorized to update users.");
        }

        AppUser user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found: " + userId));
        Role role = roleRepository.findByName(roleName).orElseThrow(() -> new RuntimeException("Role not found: " + roleName));
        Set<Department> departments = departmentNames.stream()
                .map(name -> Optional.ofNullable(departmentRepository.findByName(name))
                        .orElseThrow(() -> new RuntimeException("Department not found: " + name)))
                .collect(Collectors.toSet());

        user.setUsername(username);
        user.setRole(role);
        user.setDepartments(departments);

        return userRepository.save(user);
    }

    public void deleteUser(Long userId) {
        if (!isAuthorized()) {
            throw new SecurityException("User not authorized to delete users.");
        }
        userRepository.deleteById(userId);
    }

    private boolean isAuthorized() {
        return true;
    }


}