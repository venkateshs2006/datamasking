package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.model.Department;
import com.enterprise.seedm.repository.UserRepository;
import com.enterprise.seedm.service.DepartmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<Department>> getAllDepartments(HttpServletRequest servletRequest) {
        HttpSession session = servletRequest.getSession(false);
        if (session == null) {
            return ResponseEntity.status(401).build();
        }
        String user = (String) session.getAttribute("user");
        String role =(String) session.getAttribute("role");
        if (user != null && role != null && role.equalsIgnoreCase("Admin")) {
            return ResponseEntity.ok(departmentService.getAllDepartments());
        } else if (user != null) {
            AppUser appUser = userRepository.findByUsername(user);
            if (appUser != null && appUser.getDepartments() != null) {
                return ResponseEntity.ok(appUser.getDepartments().stream().collect(Collectors.toList()));
            } else {
                return ResponseEntity.ok(List.of());
            }
        }

        return ResponseEntity.ok(List.of());
    }
}