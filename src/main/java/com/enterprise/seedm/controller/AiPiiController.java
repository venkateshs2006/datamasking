package com.enterprise.seedm.controller;

import com.enterprise.seedm.model.AiPiiDetectionRequest;
import com.enterprise.seedm.model.AiPiiDetectionResponse;
import com.enterprise.seedm.service.AiPiiDetectorService;
import com.enterprise.seedm.service.QwenAiClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Slf4j
public class AiPiiController {

    private final AiPiiDetectorService aiPiiDetectorService;
    private final QwenAiClientService qwenAiClientService;

    @PostMapping("/detect-pii")
    public ResponseEntity<AiPiiDetectionResponse> detectPii(@RequestBody AiPiiDetectionRequest request) {
        log.info("API request received to detect PII for tables: {}", request.getTables());
        AiPiiDetectionResponse response = aiPiiDetectorService.detectPii(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/test-qwen")
    public ResponseEntity<Map<String, Object>> testQwen(@RequestBody Map<String, String> payload) {
        String apiUrl = payload.get("qwenApiUrl");
        String apiKey = payload.get("qwenApiKey");
        String model = payload.get("qwenModel");

        log.info("Testing connection to Qwen AI at: {}", apiUrl);
        boolean success = qwenAiClientService.testConnection(apiUrl, apiKey, model);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success 
                ? "Successfully connected to Qwen LLM API!" 
                : "Could not connect to Qwen LLM API. Check your Endpoint URL, API Key, or internet connection.");
        return ResponseEntity.ok(result);
    }
}
