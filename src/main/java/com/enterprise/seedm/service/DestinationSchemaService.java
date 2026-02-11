package com.enterprise.seedm.service;

import com.enterprise.seedm.model.ColumnMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;

@Service
@Slf4j
public class DestinationSchemaService {

    private final JdbcTemplate destinationJdbcTemplate;

    @Value("${migration.destination.schema}")
    private String destinationSchema;

    public DestinationSchemaService(@Qualifier("destinationDataSource") DataSource destinationDataSource) {
        this.destinationJdbcTemplate = new JdbcTemplate(destinationDataSource);
    }

    @Transactional
    public void recreateTable(String tableName, List<ColumnMetadata> columns) {
        log.info("Recreating table {}.{}", destinationSchema, tableName);

        // Drop table if exists
        String dropSql = String.format("DROP TABLE IF EXISTS %s.%s CASCADE", destinationSchema, tableName);
        destinationJdbcTemplate.execute(dropSql);

        // Create table
        StringBuilder createSql = new StringBuilder();
        createSql.append(String.format("CREATE TABLE %s.%s (", destinationSchema, tableName));

        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata col = columns.get(i);
            createSql.append(col.getColumnName()).append(" ");
            createSql.append(mapDataType(col));

            if ("NO".equals(col.getIsNullable())) {
                createSql.append(" NOT NULL");
            }

            if (i < columns.size() - 1) {
                createSql.append(", ");
            }
        }
        createSql.append(")");

        log.debug("Create SQL: {}", createSql);
        destinationJdbcTemplate.execute(createSql.toString());
    }

    private String mapDataType(ColumnMetadata col) {
        String type = col.getDataType().toLowerCase();

        switch (type) {
            case "character varying":
            case "varchar":
                return col.getCharacterMaximumLength() != null ?
                        "VARCHAR(" + col.getCharacterMaximumLength() + ")" : "TEXT";
            case "character":
            case "char":
                return "CHAR(" + (col.getCharacterMaximumLength() != null ? col.getCharacterMaximumLength() : 1) + ")";
            case "numeric":
            case "decimal":
                if (col.getNumericPrecision() != null && col.getNumericScale() != null) {
                    return "NUMERIC(" + col.getNumericPrecision() + "," + col.getNumericScale() + ")";
                }
                return "NUMERIC";
            case "integer":
            case "int4":
                return "INTEGER";
            case "bigint":
            case "int8":
                return "BIGINT";
            case "smallint":
            case "int2":
                return "SMALLINT";
            case "timestamp without time zone":
            case "timestamp":
                return "TIMESTAMP";
            case "timestamp with time zone":
            case "timestamptz":
                return "TIMESTAMPTZ";
            case "date":
                return "DATE";
            case "boolean":
            case "bool":
                return "BOOLEAN";
            case "text":
                return "TEXT";
            case "double precision":
            case "float8":
                return "DOUBLE PRECISION";
            case "real":
            case "float4":
                return "REAL";
            case "uuid":
                return "UUID";
            case "bytea":
                return "BYTEA";
            case "json":
            case "jsonb":
                return "JSONB";
            default:
                // Fallback for unknown types (might need more mapping)
                return type;
        }
    }
}
