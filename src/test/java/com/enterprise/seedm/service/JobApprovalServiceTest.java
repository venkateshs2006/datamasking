package com.enterprise.seedm.service;

import com.enterprise.seedm.model.JobRequest;
import com.enterprise.seedm.repository.JobRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class JobApprovalServiceTest {

    private JobRequestRepository repository;
    private JobApprovalService jobApprovalService;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(JobRequestRepository.class);
        jobApprovalService = new JobApprovalService(repository);
    }

    @Test
    void testSubmitJob() {
        JobRequest req = new JobRequest();
        req.setMigrationName("Finance Migration");
        req.setDepartment("Finance");
        req.setSubmittedBy("operator");

        when(repository.save(any(JobRequest.class))).thenAnswer(invocation -> {
            JobRequest saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });

        JobRequest result = jobApprovalService.submitJob(req);
        assertNotNull(result);
        assertEquals(10L, result.getId());
        assertEquals("WAITING", result.getStatus());
        assertTrue(result.getCreatedAt() > 0);
    }

    @Test
    void testApproveJob() {
        JobRequest req = new JobRequest();
        req.setId(5L);
        req.setStatus("WAITING");

        when(repository.findById(5L)).thenReturn(Optional.of(req));
        when(repository.save(any(JobRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobRequest approved = jobApprovalService.approveJob(5L, "Looks good, approved for execution.");
        assertNotNull(approved);
        assertEquals("APPROVED", approved.getStatus());
        assertEquals("Looks good, approved for execution.", approved.getComments());
    }

    @Test
    void testRejectJob() {
        JobRequest req = new JobRequest();
        req.setId(7L);
        req.setStatus("WAITING");

        when(repository.findById(7L)).thenReturn(Optional.of(req));
        when(repository.save(any(JobRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobRequest rejected = jobApprovalService.rejectJob(7L, "Missing security approval");
        assertNotNull(rejected);
        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("Missing security approval", rejected.getComments());
    }
}
