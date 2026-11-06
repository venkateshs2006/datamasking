package com.enterprise.seedm.controller;

import com.enterprise.seedm.service.CsvSecureExportService;
import com.enterprise.seedm.service.CsvSecureImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class CsvSecureExportImportControllerTest {

    private MockMvc exportMockMvc;
    private MockMvc importMockMvc;

    @Mock
    private CsvSecureExportService exportService;

    @Mock
    private CsvSecureImportService importService;

    @Mock
    private TaskExecutor taskExecutor;

    @InjectMocks
    private CsvSecureExportController exportController;

    @InjectMocks
    private CsvSecureImportController importController;

    @BeforeEach
    public void setUp() {
        exportController = new CsvSecureExportController(exportService, taskExecutor);
        importController = new CsvSecureImportController(importService, taskExecutor);
        exportMockMvc = MockMvcBuilders.standaloneSetup(exportController).build();
        importMockMvc = MockMvcBuilders.standaloneSetup(importController).build();
    }

    @Test
    public void testExportScanEndpoint() throws Exception {
        when(exportService.scanSourceFiles(any())).thenReturn(Map.of(
                "status", "SUCCESS",
                "fileCount", 1,
                "files", List.of("customers.csv")
        ));

        exportMockMvc.perform(post("/api/csv-secure-export/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"local\",\"path\":\"./test-data\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fileCount").value(1));
    }

    @Test
    public void testExportFieldsEndpoint() throws Exception {
        when(exportService.sampleCsvHeaders(any(), anyString())).thenReturn(Map.of(
                "status", "SUCCESS",
                "headers", List.of("id", "name", "email"),
                "fields", List.of("id", "name", "email"),
                "columns", List.of("id", "name", "email")
        ));

        exportMockMvc.perform(post("/api/csv-secure-export/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"customers.csv\",\"path\":\"./test-data\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fields[0]").value("id"))
                .andExpect(jsonPath("$.headers[1]").value("name"));
    }

    @Test
    public void testImportInspectEndpoint() throws Exception {
        when(importService.inspectSource(any(), any())).thenReturn(Map.of(
                "status", "SUCCESS",
                "files", List.of("staff.csv"),
                "columns", Map.of("staff.csv", List.of("id", "name", "email"))
        ));

        importMockMvc.perform(post("/api/csv-secure-import/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storage\":{\"type\":\"local\",\"path\":\"./export\"},\"secretKey\":\"Pass123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.files[0]").value("staff.csv"));
    }

    @Test
    public void testImportFieldsEndpoint() throws Exception {
        when(importService.sampleCsvHeaders(any(), anyString(), any())).thenReturn(Map.of(
                "status", "SUCCESS",
                "headers", List.of("id", "name", "salary"),
                "fields", List.of("id", "name", "salary")
        ));

        importMockMvc.perform(post("/api/csv-secure-import/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"staff.csv\",\"secretKey\":\"Pass123!\",\"path\":\"./export\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fields[2]").value("salary"));
    }
}
