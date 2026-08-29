package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.AppUser;
import com.enterprise.seedm.model.Department;
import com.enterprise.seedm.model.Role;
import com.enterprise.seedm.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class UserControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    private MockHttpSession adminSession;
    private MockHttpSession userSession;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userController).build();

        adminSession = new MockHttpSession();
        adminSession.setAttribute("user", "admin");
        adminSession.setAttribute("role", "ADMIN");

        userSession = new MockHttpSession();
        userSession.setAttribute("user", "viewer_user");
        userSession.setAttribute("role", "VIEWER");
    }

    @Test
    void testGetAllUsers_AdminAuthorized() throws Exception {
        Role role = new Role(1L, "admin");
        Department dept = new Department(1L, "Finance");
        AppUser user = new AppUser(1L, "admin", "hashed_pwd", role, Set.of(dept));

        when(userService.getAllUsers()).thenReturn(List.of(user));

        mockMvc.perform(get("/api/users").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    void testGetAllUsers_NonAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/users").session(userSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void testCreateUser_Success() throws Exception {
        Role role = new Role(2L, "manager");
        Department dept = new Department(2L, "IT");
        AppUser newUser = new AppUser(2L, "john_mgr", "hashed_pwd", role, Set.of(dept));

        when(userService.createUser(eq("john_mgr"), eq("pass123"), eq("manager"), any()))
                .thenReturn(newUser);

        mockMvc.perform(post("/api/users")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"john_mgr\",\"password\":\"pass123\",\"role\":\"manager\",\"departments\":[\"IT\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("john_mgr"))
                .andExpect(jsonPath("$.role.name").value("manager"));
    }

    @Test
    void testUpdateUser_Success() throws Exception {
        Role role = new Role(4L, "approver");
        Department dept = new Department(3L, "HR");
        AppUser updated = new AppUser(2L, "john_mgr", "new_hash", role, Set.of(dept));

        when(userService.updateUser(eq(2L), eq("john_mgr"), eq("newpass"), eq("approver"), any()))
                .thenReturn(updated);

        mockMvc.perform(put("/api/users/2")
                        .session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"john_mgr\",\"password\":\"newpass\",\"role\":\"approver\",\"departments\":[\"HR\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role.name").value("approver"));
    }

    @Test
    void testDeleteUser_Success() throws Exception {
        AppUser target = new AppUser(5L, "other_user", "pwd", new Role(3L, "scheduler"), Set.of());
        when(userService.getUser(5L)).thenReturn(target);
        doNothing().when(userService).deleteUser(5L);

        mockMvc.perform(delete("/api/users/5").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void testGetRoles_Success() throws Exception {
        when(userService.getAllRoles()).thenReturn(List.of(
                new Role(1L, "admin"),
                new Role(2L, "manager"),
                new Role(3L, "scheduler"),
                new Role(4L, "approver")
        ));

        mockMvc.perform(get("/api/users/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("admin"))
                .andExpect(jsonPath("$[1].name").value("manager"));
    }
}
