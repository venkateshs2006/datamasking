package com.enterprise.seedm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JsonMigrationConfig {
    private String sourceDir;
    private String destDir;
    
    @JsonProperty("maskingColumns")
    private List<String> maskingFields = new ArrayList<>();
    
    @JsonProperty("partialMaskingColumns")
    private List<String> partialMaskingFields = new ArrayList<>();

    private List<String> constraintFields = new ArrayList<>();
}
