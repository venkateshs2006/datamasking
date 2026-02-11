package com.enterprise.seedm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ColumnMetadata {
    private String columnName;
    private String dataType;
    private String isNullable;
    private Integer characterMaximumLength;
    private Integer numericPrecision;
    private Integer numericScale;
}
