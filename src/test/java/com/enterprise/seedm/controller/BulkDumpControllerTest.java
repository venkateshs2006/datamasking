package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.BulkDumpConfig;
import com.enterprise.seedm.model.BulkDumpProgress;
import com.enterprise.seedm.model.BulkDumpTableConfig;
import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.service.BulkDataDumpService;
import com.enterprise.seedm.service.DbConnectionService;
import com.enterprise.seedm.service.JobApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class BulkDumpControllerTest {

    private BulkDataDumpService bulkDataDumpService;
    private JobApprovalService jobApprovalService;
    private DbConnectionService dbConnectionService;
    private com.enterprise.seedm.service.CosConnectionService cosConnectionService;
    private ObjectMapper objectMapper;
    private BulkDumpController controller;

    @BeforeEach
    void setUp() {
        bulkDataDumpService = Mockito.mock(BulkDataDumpService.class);
        jobApprovalService = Mockito.mock(JobApprovalService.class);
        dbConnectionService = Mockito.mock(DbConnectionService.class);
        cosConnectionService = Mockito.mock(com.enterprise.seedm.service.CosConnectionService.class);
        objectMapper = new ObjectMapper();

        controller = new BulkDumpController(
                bulkDataDumpService,
                jobApprovalService,
                dbConnectionService,
                cosConnectionService,
                objectMapper
        );
    }

    @Test
    void testGetTablesWithCounts() {
        when(bulkDataDumpService.discoverTablesWithCounts(eq("sql"), eq(1L), eq("public"), any()))
                .thenReturn(List.of(
                        BulkDumpTableConfig.builder().tableName("accounts").currentCount(500).targetCount(100).build()
                ));

        ResponseEntity<?> response = controller.getTablesWithCounts(Map.of(
                "dbType", "sql",
                "connectionId", 1L,
                "schema", "public"
        ));

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("SUCCESS", body.get("status"));
        assertEquals(1, body.get("totalTables"));
    }

    @Test
    void testSendForApprovalAndApprove() {
        JobRequest mockJob = new JobRequest();
        mockJob.setId(99L);
        mockJob.setStatus("WAITING");
        mockJob.setMigrationName("Test Dump");
        mockJob.setJobType("BULK_DUMP_SQL");

        when(jobApprovalService.submitJob(any(JobRequest.class))).thenReturn(mockJob);

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.getSession().setAttribute("user", "testadmin");

        ResponseEntity<?> reqResp = controller.sendForApproval(Map.of(
                "jobName", "Test Dump",
                "dbType", "sql",
                "tables", List.of()
        ), servletRequest);

        assertEquals(200, reqResp.getStatusCode().value());
        Map<?, ?> reqBody = (Map<?, ?>) reqResp.getBody();
        assertEquals(99L, reqBody.get("jobId"));
        assertEquals("WAITING", reqBody.get("jobStatus"));

        // Test approve
        mockJob.setStatus("APPROVED");
        when(jobApprovalService.approveJob(eq(99L), anyString())).thenReturn(mockJob);

        ResponseEntity<?> appResp = controller.approveJob(99L, Map.of("comments", "Approved"));
        assertEquals(200, appResp.getStatusCode().value());
        Map<?, ?> appBody = (Map<?, ?>) appResp.getBody();
        assertEquals("APPROVED", appBody.get("jobStatus"));
    }

    @Test
    void testStartBulkDumpAndStatus() {
        JobRequest mockJob = new JobRequest();
        mockJob.setId(99L);
        mockJob.setStatus("APPROVED");
        mockJob.setConfigDetails(Map.of(
                "dbType", "sql",
                "tables", List.of(Map.of("tableName", "users", "targetCount", 50))
        ));

        when(jobApprovalService.getJob(99L)).thenReturn(mockJob);
        when(bulkDataDumpService.generateExecutionId()).thenReturn("bulk-dump-999");

        ResponseEntity<?> startResp = controller.startBulkDump(99L);
        assertEquals(200, startResp.getStatusCode().value());
        Map<?, ?> startBody = (Map<?, ?>) startResp.getBody();
        assertEquals("bulk-dump-999", startBody.get("executionId"));

        // Status check
        BulkDumpProgress progress = BulkDumpProgress.builder()
                .executionId("bulk-dump-999")
                .status("RUNNING")
                .overallPercent(45)
                .build();
        when(bulkDataDumpService.getProgress("bulk-dump-999")).thenReturn(progress);

        ResponseEntity<?> statusResp = controller.getExecutionStatus("bulk-dump-999");
        assertEquals(200, statusResp.getStatusCode().value());
        BulkDumpProgress statusBody = (BulkDumpProgress) statusResp.getBody();
        assertEquals(45, statusBody.getOverallPercent());
    }
}
