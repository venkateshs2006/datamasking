package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.service.CosConnectionService;
import com.enterprise.seedm.service.IbmCosService;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class CosConnectionControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CosConnectionService cosService;

    @Mock
    private IbmCosService ibmCosService;

    @InjectMocks
    private CosConnectionController cosController;

    private MockHttpSession adminSession;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(cosController).build();

        adminSession = new MockHttpSession();
        adminSession.setAttribute("user", "admin");
        adminSession.setAttribute("role", "ADMIN");
    }

    @Test
    void testGetAllConnections() throws Exception {
        CosConnection c1 = new CosConnection();
        c1.setId(1L);
        c1.setCosName("IBM Cloud Dallas Bucket");
        c1.setStorageType("COS");
        c1.setBucketName("bnp-migration-bucket");

        when(cosService.getConnectionsByFilters(any(), any(), any())).thenReturn(List.of(c1));

        mockMvc.perform(get("/api/cos-connections").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cosName").value("IBM Cloud Dallas Bucket"))
                .andExpect(jsonPath("$[0].storageType").value("COS"));
    }

    @Test
    void testTestConnectionEndpoint() throws Exception {
        when(ibmCosService.testConnection(any(CosConnection.class)))
                .thenReturn(Map.of("status", "SUCCESS", "message", "Bucket verified successfully"));

        mockMvc.perform(post("/api/cos-connections/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storageType\":\"COS\",\"bucketUrl\":\"https://s3.us-south.cloud-object-storage.appdomain.cloud\",\"bucketName\":\"test-bucket\"}")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Bucket verified successfully"));
    }

    @Test
    void testListObjectsInBucket() throws Exception {
        CosConnection conn = new CosConnection();
        conn.setId(1L);
        conn.setBucketName("test-bucket");

        when(cosService.getConnection(1L)).thenReturn(conn);
        when(ibmCosService.getEffectiveBucketName(any())).thenReturn("test-bucket");
        when(ibmCosService.listObjects(eq(conn), any())).thenReturn(List.of(
                Map.of("name", "data.json", "size", 1024L, "lastModified", "2026-08-30T10:00:00Z")
        ));

        mockMvc.perform(get("/api/cos-connections/1/objects").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[0].name").value("data.json"))
                .andExpect(jsonPath("$.objects[0].size").value(1024));
    }
}
