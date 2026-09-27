package com.enterprise.seedm.service;

import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.model.ConstraintMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Table Discovery Service
 * Discovers all tables from the source PostgreSQL schema
 */
@Service
@Slf4j
public class TableDiscoveryService {

    private final DataSource sourceDataSource;
    private final JdbcTemplate sourceJdbcTemplate;
    private final SchemaConfig schemaConfig;
    private final MaskingConfigService maskingConfigService;

    @Value("${spring.batch.jdbc.table-prefix:BATCH_}")
    private String batchTablePrefix;

    public TableDiscoveryService(@Qualifier("sourceDataSource") DataSource sourceDataSource, 
                                 SchemaConfig schemaConfig,
                                 MaskingConfigService maskingConfigService) {
        this.sourceDataSource = sourceDataSource;
        this.sourceJdbcTemplate = new JdbcTemplate(sourceDataSource);
        this.schemaConfig = schemaConfig;
        this.maskingConfigService = maskingConfigService;
    }

    /**
     * Get all table names from source schema, excluding Spring Batch tables.
     * If targetTables are defined in config, filter the result.
     */
    public List<String> discoverTables() throws SQLException {
        String sql = """
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = ? 
            AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

        List<String> tables = sourceJdbcTemplate.queryForList(sql, String.class, schemaConfig.getSourceSchema());

        // Filter out Spring Batch tables
        List<String> filteredTables = tables.stream()
                .filter(tableName -> !tableName.toLowerCase().startsWith(batchTablePrefix.toLowerCase()))
                .collect(Collectors.toList());
                
        // Filter by user selection if provided
        List<String> targetTables = maskingConfigService.getConfig().getTargetTables();
        if (targetTables != null && !targetTables.isEmpty()) {
             filteredTables = filteredTables.stream()
                     .filter(targetTables::contains)
                     .collect(Collectors.toList());
        }

        log.info("Discovered {} tables in schema '{}' ({} filtered out)",
                filteredTables.size(), schemaConfig.getSourceSchema(), tables.size() - filteredTables.size());
        filteredTables.forEach(table -> log.debug("  - {}", table));

        return filteredTables;
    }

    /**
     * Get column names for a specific table
     */
    public List<String> getTableColumns(String tableName) {
        String sql = """
            SELECT column_name 
            FROM information_schema.columns 
            WHERE table_schema = ? 
            AND table_name = ? 
            ORDER BY ordinal_position
            """;

        List<String> columns = sourceJdbcTemplate.queryForList(sql, String.class, schemaConfig.getSourceSchema(), tableName);
        log.debug("Table '{}' has {} columns", tableName, columns.size());
        return columns;
    }

    /**
     * Get detailed column metadata for a specific table
     */
    public List<ColumnMetadata> getTableColumnMetadata(String tableName) {
        String schema = schemaConfig.getSourceSchema();
        String table = tableName;
        if (tableName != null && tableName.contains(".")) {
            schema = tableName.substring(0, tableName.lastIndexOf('.'));
            table = tableName.substring(tableName.lastIndexOf('.') + 1);
        }
        return getTableColumnMetadata(schema, table);
    }

    public List<ColumnMetadata> getTableColumnMetadata(String schemaName, String tableName) {
        String sql = """
            SELECT column_name, data_type, is_nullable, character_maximum_length, 
                   numeric_precision, numeric_scale
            FROM information_schema.columns 
            WHERE table_schema = ? 
            AND table_name = ? 
            ORDER BY ordinal_position
            """;

        List<ColumnMetadata> list = sourceJdbcTemplate.query(sql, (rs, rowNum) -> new ColumnMetadata(
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getString("is_nullable"),
                rs.getObject("character_maximum_length") != null ? rs.getInt("character_maximum_length") : null,
                rs.getObject("numeric_precision") != null ? rs.getInt("numeric_precision") : null,
                rs.getObject("numeric_scale") != null ? rs.getInt("numeric_scale") : null
        ), schemaName, tableName);

        if (list.isEmpty() && tableName != null) {
            String fallbackSql = """
                SELECT column_name, data_type, is_nullable, character_maximum_length, 
                       numeric_precision, numeric_scale
                FROM information_schema.columns 
                WHERE table_name = ? 
                ORDER BY ordinal_position
                """;
            return sourceJdbcTemplate.query(fallbackSql, (rs, rowNum) -> new ColumnMetadata(
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    rs.getString("is_nullable"),
                    rs.getObject("character_maximum_length") != null ? rs.getInt("character_maximum_length") : null,
                    rs.getObject("numeric_precision") != null ? rs.getInt("numeric_precision") : null,
                    rs.getObject("numeric_scale") != null ? rs.getInt("numeric_scale") : null
            ), tableName);
        }

        return list;
    }

    /**
     * Get all foreign keys across the schema
     */
    public List<ConstraintMetadata> getAllForeignKeys() {
        String sql = """
            SELECT 
                tc.constraint_name, 
                tc.constraint_type, 
                tc.table_name, 
                kcu.column_name, 
                ccu.table_name AS foreign_table_name, 
                ccu.column_name AS foreign_column_name 
            FROM 
                information_schema.table_constraints AS tc 
                JOIN information_schema.key_column_usage AS kcu 
                  ON tc.constraint_name = kcu.constraint_name 
                  AND tc.table_schema = kcu.table_schema 
                LEFT JOIN information_schema.constraint_column_usage AS ccu 
                  ON ccu.constraint_name = tc.constraint_name 
                  AND ccu.table_schema = tc.table_schema 
            WHERE tc.constraint_type = 'FOREIGN KEY' 
            AND tc.table_schema = ?
            ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position
            """;
        try {
            List<Map<String, Object>> rows = sourceJdbcTemplate.queryForList(sql, schemaConfig.getSourceSchema());
            List<ConstraintMetadata> list = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                list.add(new ConstraintMetadata(
                        (String) row.get("constraint_name"),
                        "FOREIGN KEY",
                        (String) row.get("table_name"),
                        (String) row.get("column_name"),
                        (String) row.get("foreign_table_name"),
                        (String) row.get("foreign_column_name")
                ));
            }
            if (list.isEmpty()) {
                String fallbackSql = """
                    SELECT 
                        tc.constraint_name, 
                        tc.constraint_type, 
                        tc.table_name, 
                        kcu.column_name, 
                        ccu.table_name AS foreign_table_name, 
                        ccu.column_name AS foreign_column_name 
                    FROM 
                        information_schema.table_constraints AS tc 
                        JOIN information_schema.key_column_usage AS kcu 
                          ON tc.constraint_name = kcu.constraint_name 
                          AND tc.table_schema = kcu.table_schema 
                        LEFT JOIN information_schema.constraint_column_usage AS ccu 
                          ON ccu.constraint_name = tc.constraint_name 
                          AND ccu.table_schema = tc.table_schema 
                    WHERE tc.constraint_type = 'FOREIGN KEY' 
                    AND tc.table_schema NOT IN ('information_schema', 'pg_catalog')
                    ORDER BY tc.table_name, tc.constraint_name, kcu.ordinal_position
                    """;
                List<Map<String, Object>> fbRows = sourceJdbcTemplate.queryForList(fallbackSql);
                for (Map<String, Object> row : fbRows) {
                    list.add(new ConstraintMetadata(
                            (String) row.get("constraint_name"),
                            "FOREIGN KEY",
                            (String) row.get("table_name"),
                            (String) row.get("column_name"),
                            (String) row.get("foreign_table_name"),
                            (String) row.get("foreign_column_name")
                    ));
                }
            }
            return list;
        } catch (Exception e) {
            log.warn("Failed to query foreign keys: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get all constraints (PK, FK, Unique) for a specific table
     * Handles composite keys by aggregating columns for the same constraint name.
     */
    public List<ConstraintMetadata> getTableConstraints(String tableName) {
        String schema = schemaConfig.getSourceSchema();
        String table = tableName;
        if (tableName != null && tableName.contains(".")) {
            schema = tableName.substring(0, tableName.lastIndexOf('.'));
            table = tableName.substring(tableName.lastIndexOf('.') + 1);
        }
        return getTableConstraints(schema, table);
    }

    public List<ConstraintMetadata> getTableConstraints(String schemaName, String tableName) {
        String sql = """
            SELECT 
                tc.constraint_name, 
                tc.constraint_type, 
                tc.table_name, 
                kcu.column_name, 
                ccu.table_name AS foreign_table_name, 
                ccu.column_name AS foreign_column_name 
            FROM 
                information_schema.table_constraints AS tc 
                JOIN information_schema.key_column_usage AS kcu 
                  ON tc.constraint_name = kcu.constraint_name 
                  AND tc.table_schema = kcu.table_schema 
                LEFT JOIN information_schema.constraint_column_usage AS ccu 
                  ON ccu.constraint_name = tc.constraint_name 
                  AND ccu.table_schema = tc.table_schema 
            WHERE tc.constraint_type IN ('PRIMARY KEY', 'FOREIGN KEY', 'UNIQUE') 
            AND tc.table_schema = ?
            AND tc.table_name = ?
            ORDER BY tc.constraint_name, kcu.ordinal_position
            """;

        List<Map<String, Object>> rows = sourceJdbcTemplate.queryForList(sql, schemaName, tableName);
        if (rows.isEmpty() && tableName != null) {
            String fallbackSql = """
                SELECT 
                    tc.constraint_name, 
                    tc.constraint_type, 
                    tc.table_name, 
                    kcu.column_name, 
                    ccu.table_name AS foreign_table_name, 
                    ccu.column_name AS foreign_column_name 
                FROM 
                    information_schema.table_constraints AS tc 
                    JOIN information_schema.key_column_usage AS kcu 
                      ON tc.constraint_name = kcu.constraint_name 
                      AND tc.table_schema = kcu.table_schema 
                    LEFT JOIN information_schema.constraint_column_usage AS ccu 
                      ON ccu.constraint_name = tc.constraint_name 
                      AND ccu.table_schema = tc.table_schema 
                WHERE tc.constraint_type IN ('PRIMARY KEY', 'FOREIGN KEY', 'UNIQUE') 
                AND tc.table_name = ?
                ORDER BY tc.constraint_name, kcu.ordinal_position
                """;
            rows = sourceJdbcTemplate.queryForList(fallbackSql, tableName);
        }
        
        // Use a map to aggregate columns for composite keys
        Map<String, ConstraintMetadata> constraintMap = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            String constraintName = (String) row.get("constraint_name");
            String columnName = (String) row.get("column_name");
            String foreignColumnName = (String) row.get("foreign_column_name");

            if (constraintMap.containsKey(constraintName)) {
                // Append to existing constraint (Composite Key)
                ConstraintMetadata existing = constraintMap.get(constraintName);
                
                // Check if column already exists in the list to avoid duplicates
                if (!existing.getColumnName().contains(columnName)) {
                     existing.setColumnName(existing.getColumnName() + ", " + columnName);
                }
                
                if (foreignColumnName != null && existing.getForeignColumnName() != null && !existing.getForeignColumnName().contains(foreignColumnName)) {
                    existing.setForeignColumnName(existing.getForeignColumnName() + ", " + foreignColumnName);
                }
            } else {
                // New constraint
                ConstraintMetadata metadata = new ConstraintMetadata(
                        constraintName,
                        (String) row.get("constraint_type"),
                        (String) row.get("table_name"),
                        columnName,
                        (String) row.get("foreign_table_name"),
                        foreignColumnName
                );
                constraintMap.put(constraintName, metadata);
            }
        }

        return new ArrayList<>(constraintMap.values());
    }

    /**
     * Get row count for a table
     */
    public long getTableRowCount(String tableName) {
        String sql = String.format("SELECT COUNT(*) FROM %s.%s", schemaConfig.getSourceSchema(), tableName);
        Long count = sourceJdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0;
    }

    /**
     * Fetch sample rows for a table (capped between 1 and 10)
     */
    public List<Map<String, Object>> getSampleRows(String tableName, int limit) {
        int rowLimit = Math.min(Math.max(limit, 1), 10);
        try {
            String sql = String.format("SELECT * FROM %s.%s LIMIT %d", schemaConfig.getSourceSchema(), tableName, rowLimit);
            return sourceJdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.warn("Could not query sample rows for {}.{}: {}", schemaConfig.getSourceSchema(), tableName, e.getMessage());
            return Collections.emptyList();
        }
    }
}
