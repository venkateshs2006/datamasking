package com.enterprise.seedm.controller;

import com.enterprise.seedm.service.SecureImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class SecureImportControllerTest {

    private MockMvc mockMvc;

    @Mock
    private SecureImportService secureImportService;

    @InjectMocks
    private SecureImportController secureImportController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(secureImportController).build();
    }

    @Test
    void testScanEndpoint() throws Exception {
        when(secureImportService.scanStorage(any())).thenReturn(Map.of(
                "status", "SUCCESS",
                "fileCount", 1,
                "files", List.of(Map.of("name", "secure-export.sql.enc", "encrypted", true))
        ));

        mockMvc.perform(post("/api/secure-import/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"local\",\"path\":\"secure-export\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fileCount").value(1));
    }

    @Test
    void testStatusEndpoint() throws Exception {
        when(secureImportService.getProgress("secure-import-1")).thenReturn(Map.of(
                "executionId", "secure-import-1",
                "status", "COMPLETED",
                "totalTables", 2,
                "processedTables", 2
        ));

        mockMvc.perform(get("/api/secure-import/status/secure-import-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.processedTables").value(2));
    }
}
