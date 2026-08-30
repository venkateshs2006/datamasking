package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.service.CosConnectionService;
import com.enterprise.seedm.service.DbConnectionService;
import com.enterprise.seedm.service.IbmCosService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class FileSystemControllerTest {

    private MockMvc mockMvc;

    @Mock
    private DbConnectionService dbConnectionService;

    @Mock
    private CosConnectionService cosConnectionService;

    @Mock
    private IbmCosService ibmCosService;

    @InjectMocks
    private FileSystemController fileSystemController;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(fileSystemController).build();
    }

    @Test
    void testScanLocalDirectoryWithJsonFiles() throws Exception {
        Path jsonFile1 = tempDir.resolve("users.json");
        Files.writeString(jsonFile1, "{\"id\": 1, \"name\": \"Alice\", \"email\": \"alice@example.com\"}");

        CosConnection localConn = new CosConnection();
        localConn.setId(1L);
        localConn.setStorageType("Local");
        localConn.setStorageLocation(tempDir.toAbsolutePath().toString());

        when(cosConnectionService.getConnection(1L)).thenReturn(localConn);

        mockMvc.perform(post("/api/fs/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cosId\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fileCount").value(1))
                .andExpect(jsonPath("$.sampleKeys").isArray());
    }

    @Test
    void testScanCosStorageBucket() throws Exception {
        CosConnection cos = new CosConnection();
        cos.setId(5L);
        cos.setStorageType("COS");
        cos.setBucketName("json-cloud-bucket");

        when(cosConnectionService.getConnection(5L)).thenReturn(cos);
        when(ibmCosService.getEffectiveBucketName(any())).thenReturn("json-cloud-bucket");
        when(ibmCosService.listObjects(eq(cos), any())).thenReturn(List.of(
                Map.of("name", "orders.json", "size", 2048L)
        ));
        when(ibmCosService.extractJsonKeys(eq(cos), eq("orders.json"))).thenReturn(new TreeSet<>(List.of("orderId", "totalAmount")));

        mockMvc.perform(post("/api/fs/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cosId\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fileCount").value(1))
                .andExpect(jsonPath("$.files[0]").value("orders.json"));
    }
}
