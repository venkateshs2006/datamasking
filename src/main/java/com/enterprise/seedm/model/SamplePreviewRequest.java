package com.enterprise.seedm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SamplePreviewRequest {
    private String tableName;
    @Builder.Default
    private List<String> tables = new ArrayList<>();
    @Builder.Default
    private List<String> maskingColumns = new ArrayList<>();
    @Builder.Default
    private List<String> partialMaskingColumns = new ArrayList<>();
    @Builder.Default
    private List<String> constraintColumns = new ArrayList<>();
    @Builder.Default
    private int limit = 5;
    @Builder.Default
    private List<String> columns = new ArrayList<>();
    private String jobType;
    private Long connectionId;
    private String databaseName;
}
