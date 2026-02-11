package com.enterprise.seedm.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Table Item Writer
 * Writes data to destination PostgreSQL table using batch inserts
 */
@Slf4j
public class TableItemWriter extends JdbcBatchItemWriter<Map<String, Object>> {
    private final DataSource destinationDataSource;
    private final String schemaName;
    private final String tableName;
    private long totalWritten = 0;

    public TableItemWriter(@Qualifier("destinationDataSource") DataSource destinationDataSource,  String schemaName, String tableName) {
        this.destinationDataSource = destinationDataSource;
        this.schemaName = schemaName;
        this.tableName = tableName;

        setDataSource(destinationDataSource);
        setAssertUpdates(false);

        log.info("Created writer for table: {}.{}", schemaName, tableName);
        
        // Initialize the writer
        afterPropertiesSet();
    }

    @Override
    public void afterPropertiesSet() {
        Assert.notNull(destinationDataSource, "DataSource must be provided");
        
        // Set a dummy SQL to satisfy JdbcBatchItemWriter.afterPropertiesSet() check.
        // We will overwrite this with the actual dynamic SQL in the write() method.
        setSql("INSERT INTO DUMMY_TABLE (ID) VALUES (:id)");
        
        // Set the parameter source provider.
        // This is crucial because JdbcBatchItemWriter.afterPropertiesSet() checks if this is null
        // to determine whether to use named parameters (true) or positional parameters (false).
        setItemSqlParameterSourceProvider(item -> {
            MapSqlParameterSource params = new MapSqlParameterSource();
            item.forEach(params::addValue);
            return params;
        });
        
        // Call super.afterPropertiesSet() to initialize internal fields like namedParameterJdbcTemplate
        // and the 'usingNamedParameters' flag.
        super.afterPropertiesSet();
    }

    @Override
    public void write(Chunk<? extends Map<String, Object>> chunk) throws Exception {
        List<? extends Map<String, Object>> items = chunk.getItems();

        if (items.isEmpty()) {
            return;
        }

        // Generate INSERT SQL from first item's columns
        Map<String, Object> firstItem = items.get(0);
        String insertSql = generateInsertSql(firstItem.keySet());
        
        // Overwrite the dummy SQL with the real one
        setSql(insertSql);

        // Write the batch
        super.write(chunk);

        totalWritten += items.size();
        log.debug("Written {} rows to {}.{} (total: {})",
                items.size(), schemaName, tableName, totalWritten);
    }

    /**
     * Generate INSERT SQL dynamically based on columns
     */
    private String generateInsertSql(Set<String> columns) {
        List<String> columnList = new ArrayList<>(columns);

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(schemaName).append(".").append(tableName);
        sql.append(" (");
        sql.append(String.join(", ", columnList));
        sql.append(") VALUES (");

        List<String> placeholders = new ArrayList<>();
        for (String column : columnList) {
            placeholders.add(":" + column);
        }
        sql.append(String.join(", ", placeholders));
        sql.append(")");

        log.debug("Generated SQL: {}", sql);
        return sql.toString();
    }

    public long getTotalWritten() {
        return totalWritten;
    }
}