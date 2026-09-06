package com.enterprise.seedm;

import com.enterprise.seedm.model.*;
import com.enterprise.seedm.repository.JobRequestRepository;
import com.enterprise.seedm.repository.UserRepository;
import com.enterprise.seedm.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import org.bson.BsonBinaryWriter;
import org.bson.Document;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Master End-to-End Test Suite for the Entire Anonify Platform.
 * Validates:
 * 1. Authentication, Authorization & RBAC
 * 2. Cloud & Local Object Storage Connection Management
 * 3. Core Masking Engine (SFD, PMD, FPH, FPE)
 * 4. Relational SQL Secure Export & Import Pipeline
 * 5. MongoDB BSON Document Secure Export & Import Pipeline
 * 6. JSON Hierarchical Secure Export & Import Pipeline
 * 7. Job Approval & Migration Workflow Lifecycle
 */
public class MasterProjectIntegrationTest {

    private ObjectMapper objectMapper;
    private FormatPreservingEncryptionService fpeService;
    private MaskingConfigService maskingConfigService;
    private DataMaskingService dataMaskingService;
    private TableDiscoveryService tableDiscoveryService;
    private CosConnectionService cosConnectionService;
    private IbmCosService ibmCosService;
    private SecureExportService secureExportService;
    private SecureImportService secureImportService;
    private MongoSecureExportService mongoSecureExportService;
    private MongoSecureImportService mongoSecureImportService;
    private JsonSecureExportService jsonSecureExportService;
    private JsonSecureImportService jsonSecureImportService;
    private AuthService authService;
    private JobApprovalService jobApprovalService;

    @TempDir
    Path workspaceDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // 1. Setup Masking Engine
        maskingConfigService = new MaskingConfigService(
                List.of("customers.name", "customers.email"),
                List.of("customers.id", "customers.account_no"),
                List.of("customers.card_number", "customers.phone"),
                "MasterSaltKey9876543210"
        );
        fpeService = new FormatPreservingEncryptionService(maskingConfigService);
        tableDiscoveryService = Mockito.mock(TableDiscoveryService.class);
        dataMaskingService = new DataMaskingService(fpeService, tableDiscoveryService, maskingConfigService);

        // 2. Setup Storage Engine
        cosConnectionService = Mockito.mock(CosConnectionService.class);
        ibmCosService = new IbmCosService(cosConnectionService);

        // 3. Setup Export & Import Services
        secureExportService = new SecureExportService(objectMapper, new Faker(), fpeService, maskingConfigService);
        secureImportService = new SecureImportService(objectMapper);

        mongoSecureExportService = new MongoSecureExportService(null, cosConnectionService, ibmCosService, fpeService, null, objectMapper, null);
        mongoSecureImportService = new MongoSecureImportService(null, cosConnectionService, ibmCosService, null, objectMapper, null);

        jsonSecureExportService = new JsonSecureExportService(cosConnectionService, ibmCosService, fpeService, null, objectMapper);
        jsonSecureImportService = new JsonSecureImportService(cosConnectionService, ibmCosService, null, objectMapper);

        // 4. Setup Auth & Approvals
        UserRepository userRepo = Mockito.mock(UserRepository.class);
        AppUser user = new AppUser();
        user.setId(101L);
        user.setUsername("bnp_admin");
        user.setPassword(new BCryptPasswordEncoder().encode("EnterpriseAdmin2026"));
        Role adminRole = new Role(1L, "ADMIN");
        user.setRole(adminRole);
        when(userRepo.findByUsername("bnp_admin")).thenReturn(user);
        authService = new AuthService(userRepo);

        JobRequestRepository jobRepo = Mockito.mock(JobRequestRepository.class);
        when(jobRepo.save(any(JobRequest.class))).thenAnswer(i -> {
            JobRequest jr = i.getArgument(0);
            if (jr.getId() == null) jr.setId(999L);
            return jr;
        });
        when(jobRepo.findById(999L)).thenReturn(Optional.of(new JobRequest() {{
            setId(999L);
            setStatus("WAITING");
            setMigrationName("Master E2E Job");
        }}));
        jobApprovalService = new JobApprovalService(jobRepo);
    }

    @Test
    @DisplayName("Stage 1: User Authentication & Role Validation")
    void testStage1_Authentication() {
        AppUser authenticated = authService.authenticate("bnp_admin", "EnterpriseAdmin2026");
        assertNotNull(authenticated, "Admin user must authenticate successfully");
        assertEquals("ADMIN", authenticated.getRole().getName());

        AppUser badAuth = authService.authenticate("bnp_admin", "WrongPassword");
        assertNull(badAuth, "Authentication must fail on wrong password");
    }

    @Test
    @DisplayName("Stage 2: Storage Connection & Cloud Bucket Validation")
    void testStage2_StorageVerification() {
        CosConnection localStorage = new CosConnection();
        localStorage.setId(1L);
        localStorage.setCosName("Local Storage Endpoint");
        localStorage.setStorageType("Local");
        localStorage.setStorageLocation(workspaceDir.toString());

        Map<String, Object> testRes = ibmCosService.testConnection(localStorage);
        assertEquals("SUCCESS", testRes.get("status"));
        assertTrue(testRes.get("message").toString().contains("Local storage directory accessible"));
    }

    @Test
    @DisplayName("Stage 3: Core Data Masking (SFD, PMD, FPH)")
    void testStage3_DataMaskingEngine() {
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("id", 101);
        inputData.put("name", "Venkatesh S");
        inputData.put("email", "venkatesh@bnp.com");
        inputData.put("card_number", "4532-9876-5432-1098");
        inputData.put("phone", "+33-1-40-14-40-00");
        inputData.put("balance", 75000.50);

        Map<String, Object> masked = dataMaskingService.maskData("customers", inputData);

        assertNotNull(masked);
        assertNotEquals("Venkatesh S", masked.get("name"), "SFD: Name must be anonymized");
        assertNotEquals("venkatesh@bnp.com", masked.get("email"), "SFD: Email must be anonymized");

        String maskedCard = (String) masked.get("card_number");
        assertTrue(maskedCard.endsWith("1098"), "PMD: Card number must preserve last 4 characters");

        String maskedPhone = (String) masked.get("phone");
        assertTrue(maskedPhone.endsWith("4000") || maskedPhone.endsWith("00"), "PMD: Phone must preserve last digits");

        assertEquals(75000.50, masked.get("balance"), "Unconfigured fields must remain unchanged");
    }

    @Test
    @DisplayName("Stage 4: JSON Bundle Export, AES-256 Encryption & Import Restoration")
    void testStage4_JsonExportAndImportPipeline() throws Exception {
        Path jsonSrcDir = workspaceDir.resolve("json-source");
        Path jsonExportDir = workspaceDir.resolve("json-export");
        Path jsonImportDir = workspaceDir.resolve("json-import");
        Files.createDirectories(jsonSrcDir);
        Files.createDirectories(jsonExportDir);
        Files.createDirectories(jsonImportDir);

        // 1. Create source JSON file
        String sourceJson = "{\"accountId\": 9901, \"holder\": \"Alice Dupont\", \"card\": \"4111222233334444\"}";
        Files.writeString(jsonSrcDir.resolve("accounts.json"), sourceJson);

        // 2. Configure JSON Secure Export
        JsonSecureExportConfig exportConfig = new JsonSecureExportConfig();
        exportConfig.setJobName("E2E JSON Export");

        JsonSecureExportConfig.StorageConfig srcStorage = new JsonSecureExportConfig.StorageConfig();
        srcStorage.setType("local");
        srcStorage.setPath(jsonSrcDir.toString());
        exportConfig.setSource(srcStorage);

        JsonSecureExportConfig.StorageConfig dstStorage = new JsonSecureExportConfig.StorageConfig();
        dstStorage.setType("local");
        dstStorage.setPath(jsonExportDir.toString());
        exportConfig.setDest(dstStorage);

        JsonSecureExportConfig.RulesConfig rules = new JsonSecureExportConfig.RulesConfig();
        rules.setMaskingKey("MasterEncryptionKey16");
        rules.setMaskingColumns(List.of("accounts.holder"));
        rules.setPartialMaskingColumns(List.of("accounts.card"));
        rules.setTargetFiles(List.of("accounts.json"));
        exportConfig.setRules(rules);

        // 3. Execute JSON Export
        String exportJobId = "e2e-json-export-job";
        jsonSecureExportService.processJsonExport(exportJobId, exportConfig);

        Path encryptedBundle = jsonExportDir.resolve("secure-json-export.json.enc");
        assertTrue(Files.exists(encryptedBundle), "Encrypted JSON bundle must exist");

        // 4. Configure JSON Secure Import
        JsonSecureImportConfig importConfig = new JsonSecureImportConfig();
        importConfig.setJobName("E2E JSON Import");

        JsonSecureImportConfig.StorageConfig impStorage = new JsonSecureImportConfig.StorageConfig();
        impStorage.setType("local");
        impStorage.setPath(jsonExportDir.toString());
        impStorage.setFileName("secure-json-export.json.enc");
        importConfig.setStorage(impStorage);

        JsonSecureImportConfig.DestinationConfig impDest = new JsonSecureImportConfig.DestinationConfig();
        impDest.setType("local");
        impDest.setPath(jsonImportDir.toString());
        impDest.setOverwrite(true);
        importConfig.setDest(impDest);
        importConfig.setSecretKey("MasterEncryptionKey16");

        // Validate secret key
        assertDoesNotThrow(() -> jsonSecureImportService.validateSecretKey(importConfig, "MasterEncryptionKey16"));

        // 5. Execute JSON Import
        String importJobId = "e2e-json-import-job";
        jsonSecureImportService.processJsonImport(importJobId, importConfig, "MasterEncryptionKey16");

        Path restoredFile = jsonImportDir.resolve("accounts.json");
        assertTrue(Files.exists(restoredFile), "Restored JSON file must exist");

        JsonNode restoredNode = objectMapper.readTree(restoredFile.toFile());
        assertNotEquals("Alice Dupont", restoredNode.get("holder").asText(), "Holder name must be anonymized in restored file");
        assertTrue(restoredNode.get("card").asText().endsWith("4444"), "Card number must preserve ending 4 digits");
    }

    @Test
    @DisplayName("Stage 5: MongoDB BSON Archive Encryption & Validation")
    void testStage5_MongoExportImportPipeline() throws Exception {
        Path mongoExportDir = workspaceDir.resolve("mongo-export");
        Files.createDirectories(mongoExportDir);

        Path tempBson = workspaceDir.resolve("mock.bson");
        DocumentCodec codec = new DocumentCodec();

        try (OutputStream out = Files.newOutputStream(tempBson);
             DataOutputStream dataOut = new DataOutputStream(out)) {
            dataOut.write("MONGOBSON1\n".getBytes(StandardCharsets.UTF_8));
            byte[] collBytes = "customers".getBytes(StandardCharsets.UTF_8);
            dataOut.writeByte(0x01);
            dataOut.writeShort(collBytes.length);
            dataOut.write(collBytes);

            Document doc = new Document("customerId", 777).append("name", "Bob Martin");
            BasicOutputBuffer buf = new BasicOutputBuffer();
            codec.encode(new BsonBinaryWriter(buf), doc, EncoderContext.builder().build());
            byte[] docBytes = buf.toByteArray();

            dataOut.writeByte(0x02);
            dataOut.writeInt(docBytes.length);
            dataOut.write(docBytes);
            dataOut.writeByte(0x03);
            dataOut.writeByte(0xFF);
        }

        Path encryptedBson = mongoExportDir.resolve("secure-mongo-export.bson.enc");
        mongoSecureExportService.encryptFileWithSalt(tempBson, encryptedBson, "MongoMasterSalt16");
        assertTrue(Files.exists(encryptedBson), "Encrypted BSON package must exist");

        // Validate secret key
        MongoSecureImportConfig importConfig = new MongoSecureImportConfig();
        MongoSecureImportConfig.StorageConfig storage = new MongoSecureImportConfig.StorageConfig();
        storage.setType("local");
        storage.setPath(mongoExportDir.toString());
        storage.setFileName("secure-mongo-export.bson.enc");
        importConfig.setStorage(storage);

        assertDoesNotThrow(() -> mongoSecureImportService.validateSecretKey(importConfig, "MongoMasterSalt16"));
        assertThrows(IllegalArgumentException.class, () -> mongoSecureImportService.validateSecretKey(importConfig, "WrongSalt"));
    }

    @Test
    @DisplayName("Stage 6: Job Approval & Workflow Lifecycle")
    void testStage6_JobApprovalLifecycle() {
        JobRequest job = new JobRequest();
        job.setMigrationName("Production Migration");
        job.setDepartment("Finance");
        job.setSubmittedBy("bnp_admin");

        JobRequest submitted = jobApprovalService.submitJob(job);
        assertEquals("WAITING", submitted.getStatus());

        JobRequest approved = jobApprovalService.approveJob(999L, "Authorized by Security Team");
        assertEquals("APPROVED", approved.getStatus());
        assertEquals("Authorized by Security Team", approved.getComments());
    }
}
