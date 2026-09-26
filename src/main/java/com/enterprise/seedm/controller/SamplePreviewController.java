package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.SamplePreviewRequest;
import com.enterprise.seedm.model.SamplePreviewResponse;
import com.enterprise.seedm.service.SamplePreviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/preview")
@RequiredArgsConstructor
@Slf4j
public class SamplePreviewController {

    private final SamplePreviewService samplePreviewService;

    @PostMapping("/sample")
    public ResponseEntity<SamplePreviewResponse> getSamplePreview(@RequestBody SamplePreviewRequest request) {
        log.info("Request received for sample data preview on table: {}", request.getTableName());
        SamplePreviewResponse response = samplePreviewService.generatePreview(request);
        return ResponseEntity.ok(response);
    }
}
