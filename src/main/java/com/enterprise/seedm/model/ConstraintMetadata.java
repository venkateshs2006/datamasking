package com.enterprise.seedm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConstraintMetadata {
    private String constraintName;
    private String constraintType; // PRIMARY KEY, FOREIGN KEY, UNIQUE
    private String tableName;
    private String columnName;
    private String foreignTableName;
    private String foreignColumnName;
}
