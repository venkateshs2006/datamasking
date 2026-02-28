package com.enterprise.seedm.service;

import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.model.ConstraintMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;

@Service
@Slf4j
public class DestinationSchemaService {

    private final JdbcTemplate destinationJdbcTemplate;
    private final SchemaConfig schemaConfig;

    public DestinationSchemaService(@Qualifier("destinationDataSource") DataSource destinationDataSource, SchemaConfig schemaConfig) {
        this.destinationJdbcTemplate = new JdbcTemplate(destinationDataSource);
        this.schemaConfig = schemaConfig;
    }

    @Transactional
    public void recreateTable(String tableName, List<ColumnMetadata> columns) {
        log.info("Recreating table {}.{}", schemaConfig.getDestinationSchema(), tableName);

        // Drop table if exists
        String dropSql = String.format("DROP TABLE IF EXISTS %s.%s CASCADE", schemaConfig.getDestinationSchema(), tableName);
        destinationJdbcTemplate.execute(dropSql);

        // Create table
        StringBuilder createSql = new StringBuilder();
        createSql.append(String.format("CREATE TABLE %s.%s (", schemaConfig.getDestinationSchema(), tableName));

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

    @Transactional
    public void createConstraints(String tableName, List<ConstraintMetadata> constraints) {
        log.info("Creating constraints for table {}.{}", schemaConfig.getDestinationSchema(), tableName);

        for (ConstraintMetadata constraint : constraints) {
            boolean success = false;
            int attempts = 0;
            int maxRetries = 3;

            while (!success && attempts < maxRetries) {
                try {
                    attempts++;
                    if (constraintExists(tableName, constraint.getConstraintName())) {
                        log.info("Constraint {} on table {} already exists. Skipping.", constraint.getConstraintName(), tableName);
                        success = true;
                        continue;
                    }

                    String sql = generateConstraintSql(tableName, constraint);
                    if (sql != null) {
                        log.debug("Executing constraint SQL (Attempt {}): {}", attempts, sql);
                        destinationJdbcTemplate.execute(sql);
                        success = true;
                        log.info("Successfully created constraint {} on table {}", constraint.getConstraintName(), tableName);
                    }
                } catch (DataAccessException e) {
                    log.warn("Failed to create constraint {} on table {} (Attempt {}/{}): {}", 
                            constraint.getConstraintName(), tableName, attempts, maxRetries, e.getMessage());
                    
                    if (attempts < maxRetries) {
                        try {
                            Thread.sleep(1000); // Wait before retry
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        log.error("Permanently failed to create constraint {} on table {} after {} attempts.", 
                                constraint.getConstraintName(), tableName, maxRetries);
                        // We don't rethrow here to allow other constraints to be processed
                    }
                }
            }
        }
    }

    private boolean constraintExists(String tableName, String constraintName) {
        String sql = """
            SELECT COUNT(*) 
            FROM information_schema.table_constraints 
            WHERE table_schema = ? 
            AND table_name = ? 
            AND constraint_name = ?
            """;
        Integer count = destinationJdbcTemplate.queryForObject(sql, Integer.class, schemaConfig.getDestinationSchema(), tableName, constraintName);
        return count != null && count > 0;
    }

    private String generateConstraintSql(String tableName, ConstraintMetadata constraint) {
        String constraintName = constraint.getConstraintName();
        
        switch (constraint.getConstraintType()) {
            case "PRIMARY KEY":
                return String.format("ALTER TABLE %s.%s ADD CONSTRAINT %s PRIMARY KEY (%s)",
                        schemaConfig.getDestinationSchema(), tableName, constraintName, constraint.getColumnName());
            
            case "UNIQUE":
                return String.format("ALTER TABLE %s.%s ADD CONSTRAINT %s UNIQUE (%s)",
                        schemaConfig.getDestinationSchema(), tableName, constraintName, constraint.getColumnName());
            
            case "FOREIGN KEY":
                return String.format("ALTER TABLE %s.%s ADD CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s.%s (%s)",
                        schemaConfig.getDestinationSchema(), tableName, constraintName, constraint.getColumnName(),
                        schemaConfig.getDestinationSchema(), constraint.getForeignTableName(), constraint.getForeignColumnName());
                
            default:
                return null;
        }
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
