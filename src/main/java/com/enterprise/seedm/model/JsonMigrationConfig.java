package com.enterprise.seedm.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JsonMigrationConfig {
    private String sourceDir;
    private String destDir;
    private List<String> maskingFields = new ArrayList<>();
    private List<String> partialMaskingFields = new ArrayList<>();
    private List<String> constraintFields = new ArrayList<>();
}
