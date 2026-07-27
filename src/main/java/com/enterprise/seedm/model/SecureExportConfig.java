package com.enterprise.seedm.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SecureExportConfig {
    private SourceConfig source;
    private DestinationConfig dest;
    private RulesConfig rules;

    @Data
    public static class SourceConfig {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
    }

    @Data
    public static class DestinationConfig {
        private String destDir;
    }

    @Data
    public static class RulesConfig {
        private List<String> maskingColumns;
        private List<String> partialMaskingColumns;
        private List<String> constraintFields;
    }
}