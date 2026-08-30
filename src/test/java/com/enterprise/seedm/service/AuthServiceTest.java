package com.enterprise.seedm.service;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.model.Department;
import com.enterprise.seedm.model.Role;
import com.enterprise.seedm.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

public class AuthServiceTest {

    private UserRepository userRepository;
    private AuthService authService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        authService = new AuthService(userRepository);
    }

    @Test
    void testAuthenticateSuccess() {
        AppUser mockUser = new AppUser();
        mockUser.setId(1L);
        mockUser.setUsername("admin");
        mockUser.setPassword(passwordEncoder.encode("SecretPass123"));
        Role role = new Role(1L, "ADMIN");
        mockUser.setRole(role);

        when(userRepository.findByUsername("admin")).thenReturn(mockUser);

        AppUser result = authService.authenticate("admin", "SecretPass123");
        assertNotNull(result);
        assertEquals("admin", result.getUsername());
        assertEquals("ADMIN", result.getRole().getName());
    }

    @Test
    void testAuthenticateInvalidPassword() {
        AppUser mockUser = new AppUser();
        mockUser.setUsername("operator");
        mockUser.setPassword(passwordEncoder.encode("CorrectPassword"));

        when(userRepository.findByUsername("operator")).thenReturn(mockUser);

        AppUser result = authService.authenticate("operator", "WrongPassword");
        assertNull(result, "Authentication should return null on incorrect password");
    }

    @Test
    void testAuthenticateUserNotFound() {
        when(userRepository.findByUsername("nonexistent")).thenReturn(null);

        AppUser result = authService.authenticate("nonexistent", "SomePass");
        assertNull(result, "Authentication should return null when user does not exist");
    }
}
