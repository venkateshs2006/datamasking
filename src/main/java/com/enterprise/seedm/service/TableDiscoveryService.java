package com.enterprise.seedm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

/**
 * Table Discovery Service
 * Discovers all tables from the source PostgreSQL schema
 */
@Service
@Slf4j
public class TableDiscoveryService {

    private final DataSource sourceDataSource;
    private final DataSource destinationDataSource;
    
    @Value("${migration.source.schema}")
    private String sourceSchema;
    
    @Value("${migration.destination.schema}")
    private String destinationSchema;
    
    private JdbcTemplate sourceJdbcTemplate;
    private JdbcTemplate destinationJdbcTemplate;

    public TableDiscoveryService(@Qualifier("sourceDataSource") DataSource sourceDataSource,
                                 @Qualifier("destinationDataSource") DataSource destinationDataSource) {
        this.sourceDataSource = sourceDataSource;
        this.destinationDataSource = destinationDataSource;
    }

    /**
     * Get all table names from source schema
     */
    public List<String> discoverTables() throws SQLException {
        sourceJdbcTemplate = new JdbcTemplate(sourceDataSource);

        String sql = """
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = ? 
            AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

        List<String> tables = sourceJdbcTemplate.queryForList(sql, String.class, sourceSchema);

        log.info("Discovered {} tables in schema '{}' in url {}", tables.size(), sourceSchema,  sourceJdbcTemplate.getDataSource().getConnection().getMetaData().getURL());
        tables.forEach(table -> log.debug("  - {}", table));

        return tables;
    }
    public List<String> discoverDestinationTables() throws SQLException {
        destinationJdbcTemplate = new JdbcTemplate(destinationDataSource);

        String sql = """
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = ? 
            AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

        List<String> tables = destinationJdbcTemplate.queryForList(sql, String.class, destinationSchema);

        log.info("Discovered Destination {} tables in schema '{}' in url {}", tables.size(), destinationSchema, destinationJdbcTemplate.getDataSource().getConnection().getMetaData().getURL());
        tables.forEach(table -> log.debug("  - {}", table));

        return tables;
    }
    /**
     * Get column names for a specific table
     */
    public List<String> getTableColumns(String tableName) {
        sourceJdbcTemplate = new JdbcTemplate(sourceDataSource);

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
     * Get row count for a table
     */
    public long getTableRowCount(String tableName) {
        sourceJdbcTemplate = new JdbcTemplate(sourceDataSource);

        String sql = String.format("SELECT COUNT(*) FROM %s.%s", sourceSchema, tableName);

        Long count = sourceJdbcTemplate.queryForObject(sql, Long.class);

        return count != null ? count : 0;
    }

}
