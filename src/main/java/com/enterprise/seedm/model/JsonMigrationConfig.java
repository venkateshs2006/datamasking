package com.enterprise.seedm.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JsonMigrationConfig {
    private String sourceDir;
    private String destDir;
    private List<String> maskingColumns = new ArrayList<>();
    private List<String> partialMaskingColumns = new ArrayList<>();
}
