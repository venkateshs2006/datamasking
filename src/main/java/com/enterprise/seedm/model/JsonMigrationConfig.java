package com.enterprise.seedm.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class JsonMigrationConfig {
    private SourceConfig source;
    private DestConfig dest;
    private RulesConfig rules;

    @Data
    public static class SourceConfig {
        private String id;
        @JsonProperty("source_dir")
        private String sourceDir;
        private String storageType;
        @JsonProperty("authentication_type")
        private String authenticationType;
        @JsonProperty("api_key")
        private String apiKey;
        @JsonProperty("service_instance_id")
        private String serviceInstanceId;
        @JsonProperty("bucket_url_endpoint")
        private String bucketUrlEndpoint;
        private String location;
        @JsonProperty("bucket_name")
        private String bucketName;
        @JsonProperty("bucket_id")
        private String bucketId;
    }

    @Data
    public static class DestConfig {
        private String id;
        @JsonProperty("dest_dir")
        private String destDir;
        private String storageType;
        @JsonProperty("authentication_type")
        private String authenticationType;
        @JsonProperty("api_key")
        private String apiKey;
        @JsonProperty("service_instance_id")
        private String serviceInstanceId;
        @JsonProperty("bucket_url_endpoint")
        private String bucketUrlEndpoint;
        private String location;
        @JsonProperty("bucket_name")
        private String bucketName;
        @JsonProperty("bucket_id")
        private String bucketId;
    }

    @Data
    public static class RulesConfig {
        private List<String> maskingColumns = new ArrayList<>();
        private List<String> partialMaskingColumns = new ArrayList<>();
        private List<String> constraintFields = new ArrayList<>();
        private String maskingKey;
    }
}