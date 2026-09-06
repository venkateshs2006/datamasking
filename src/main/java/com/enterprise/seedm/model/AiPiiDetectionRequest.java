package com.enterprise.seedm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiPiiDetectionRequest {
    private List<String> tables;
    private Long sourceConnectionId;
    private String sourceSchema;
    private Map<String, List<String>> tableColumns;
    
    // Qwen AI LLM Configuration
    private String qwenApiUrl;
    private String qwenApiKey;
    private String qwenModel;
}
