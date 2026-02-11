package com.enterprise.seedm.service;

import com.enterprise.seedm.model.ColumnMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
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

    @Value("${migration.source.schema}")
    private String sourceSchema;

    @Value("${spring.batch.jdbc.table-prefix:BATCH_}")
    private String batchTablePrefix;

    public TableDiscoveryService(@Qualifier("sourceDataSource") DataSource sourceDataSource) {
        this.sourceDataSource = sourceDataSource;
        this.sourceJdbcTemplate = new JdbcTemplate(sourceDataSource);
    }

    /**
     * Get all table names from source schema, excluding Spring Batch tables.
     */
    public List<String> discoverTables() throws SQLException {
        String sql = """
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = ? 
            AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

        List<String> tables = sourceJdbcTemplate.queryForList(sql, String.class, sourceSchema);

        // Filter out Spring Batch tables
        List<String> filteredTables = tables.stream()
                .filter(tableName -> !tableName.toLowerCase().startsWith(batchTablePrefix.toLowerCase()))
                .collect(Collectors.toList());

        log.info("Discovered {} tables in schema '{}' ({} filtered out)",
                filteredTables.size(), sourceSchema, tables.size() - filteredTables.size());
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

        List<String> columns = sourceJdbcTemplate.queryForList(sql, String.class, sourceSchema, tableName);
        log.debug("Table '{}' has {} columns", tableName, columns.size());
        return columns;
    }

    /**
     * Get detailed column metadata for a specific table
     */
    public List<ColumnMetadata> getTableColumnMetadata(String tableName) {
        String sql = """
            SELECT column_name, data_type, is_nullable, character_maximum_length, 
                   numeric_precision, numeric_scale
            FROM information_schema.columns 
            WHERE table_schema = ? 
            AND table_name = ? 
            ORDER BY ordinal_position
            """;

        return sourceJdbcTemplate.query(sql, (rs, rowNum) -> new ColumnMetadata(
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getString("is_nullable"),
                rs.getObject("character_maximum_length") != null ? rs.getInt("character_maximum_length") : null,
                rs.getObject("numeric_precision") != null ? rs.getInt("numeric_precision") : null,
                rs.getObject("numeric_scale") != null ? rs.getInt("numeric_scale") : null
        ), sourceSchema, tableName);
    }

    /**
     * Get row count for a table
     */
    public long getTableRowCount(String tableName) {
        String sql = String.format("SELECT COUNT(*) FROM %s.%s", sourceSchema, tableName);
        Long count = sourceJdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0;
    }
}
