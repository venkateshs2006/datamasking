package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class IbmCosServiceTest {

    private CosConnectionService cosConnectionService;
    private IbmCosService ibmCosService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        cosConnectionService = mock(CosConnectionService.class);
        ibmCosService = new IbmCosService(cosConnectionService);
    }

    @Test
    void testBuildS3ClientHmac() {
        CosConnection conn = new CosConnection();
        conn.setId(1L);
        conn.setStorageType("COS");
        conn.setAuthenticationType("HMAC");
        conn.setAccessKey("my-access-key-12345");
        conn.setSecretKey("my-secret-key-67890");
        conn.setBucketUrl("https://s3.us-south.cloud-object-storage.appdomain.cloud");
        conn.setLocation("us-south");
        conn.setBucketName("finance-archive-bucket");

        S3Client client = ibmCosService.buildS3Client(conn);
        assertNotNull(client, "S3Client should be built successfully for HMAC");
    }

    @Test
    void testBuildS3ClientIam() {
        CosConnection conn = new CosConnection();
        conn.setId(2L);
        conn.setStorageType("COS");
        conn.setAuthenticationType("IAM");
        conn.setApiKey("test-iam-api-key");
        conn.setServiceInstanceId("crn:v1:bluemix:public:cloud-object-storage:global:a/12345:67890::");
        conn.setBucketUrl("https://s3.direct.us-south.cloud-object-storage.appdomain.cloud");
        conn.setLocation("us-south");
        conn.setBucketName("test-iam-bucket");

        S3Client client = ibmCosService.buildS3Client(conn);
        assertNotNull(client, "S3Client should be built successfully for IAM");
    }

    @Test
    void testTestConnectionLocalStorage() {
        CosConnection localConn = new CosConnection();
        localConn.setStorageType("Local");
        localConn.setStorageLocation(tempDir.toString());

        Map<String, Object> res = ibmCosService.testConnection(localConn);
        assertEquals("SUCCESS", res.get("status"));
        assertTrue(res.get("message").toString().contains("Local storage directory accessible"));
    }

    @Test
    void testUploadAndDownloadLocalStorage() throws IOException {
        Path testFile = tempDir.resolve("sample.json");
        Files.writeString(testFile, "{\"customer\": {\"id\": 101, \"name\": \"Alice Smith\"}}");

        CosConnection localConn = new CosConnection();
        localConn.setStorageType("Local");
        localConn.setStorageLocation(tempDir.resolve("target-store").toString());

        // Upload
        ibmCosService.uploadFile(localConn, "uploaded-sample.json", testFile);
        Path targetFile = tempDir.resolve("target-store").resolve("uploaded-sample.json");
        assertTrue(Files.exists(targetFile), "File should be stored in destination");

        // Download
        Path downloadedFile = tempDir.resolve("downloaded-sample.json");
        ibmCosService.downloadFile(localConn, "uploaded-sample.json", downloadedFile);
        assertTrue(Files.exists(downloadedFile), "File should be downloaded successfully");

        // JSON Key Discovery
        Set<String> keys = ibmCosService.extractJsonKeys(localConn, "uploaded-sample.json");
        assertTrue(keys.contains("customer.id"));
        assertTrue(keys.contains("customer.name"));

        // List objects
        List<Map<String, Object>> list = ibmCosService.listObjects(localConn, null);
        assertFalse(list.isEmpty());
        assertTrue(list.stream().anyMatch(o -> "uploaded-sample.json".equals(o.get("name"))));
    }
}
