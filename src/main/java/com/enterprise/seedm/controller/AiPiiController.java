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

    @PostMapping("/detect-heuristic")
    public ResponseEntity<AiPiiDetectionResponse> detectHeuristic(@RequestBody AiPiiDetectionRequest request) {
        log.info("API request received for direct heuristic PII detection for tables: {}", request.getTables());
        AiPiiDetectionResponse response = aiPiiDetectorService.detectPiiWithHeuristicsOnly(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/test-qwen")
    public ResponseEntity<Map<String, Object>> testQwen(@RequestBody Map<String, String> payload) {
        String apiUrl = (payload.get("qwenApiUrl") != null && !payload.get("qwenApiUrl").isBlank())
                ? payload.get("qwenApiUrl").trim() : QwenAiClientService.DEFAULT_QWEN_URL;
        String apiKey = (payload.get("qwenApiKey") != null && !payload.get("qwenApiKey").isBlank())
                ? payload.get("qwenApiKey").trim() : System.getenv().getOrDefault("QWEN_API_KEY", System.getProperty("QWEN_API_KEY", ""));
        String model = (payload.get("qwenModel") != null && !payload.get("qwenModel").isBlank())
                ? payload.get("qwenModel").trim() : QwenAiClientService.DEFAULT_QWEN_MODEL;

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
