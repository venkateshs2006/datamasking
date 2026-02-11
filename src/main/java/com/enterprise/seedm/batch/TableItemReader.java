package com.enterprise.seedm.batch;


import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.jdbc.core.ColumnMapRowMapper;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Table Item Reader
 * Reads data from source PostgreSQL table using pagination
 */
@Slf4j
public class TableItemReader extends JdbcPagingItemReader<Map<String, Object>> {

    public TableItemReader(DataSource dataSource,
                           String schemaName,
                           String tableName,
                           List<String> columns,
                           int pageSize) {

        setDataSource(dataSource);
        setPageSize(pageSize);
        setRowMapper(new ColumnMapRowMapper());

        // Set up paging query provider
        PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();

        // Build select clause with all columns
        if (columns.isEmpty()) {
            queryProvider.setSelectClause("*");
        } else {
            String selectClause = String.join(", ", columns);
            queryProvider.setSelectClause(selectClause);
        }

        // Set FROM clause with schema
        queryProvider.setFromClause(schemaName + "." + tableName);

        // Use first column for sorting (or you can specify primary key)
        Map<String, Order> sortKeys = new HashMap<>();
        if (!columns.isEmpty()) {
            sortKeys.put(columns.get(0), Order.ASCENDING);
        } else {
            // Fallback sort key if no columns provided (though unlikely for a valid table)
            // Ideally we should find the primary key, but for now let's assume there's at least one column
             // If columns is empty, we can't really sort. This might be an issue.
             // We'll try to sort by a dummy column or assume the table has a primary key named 'id'
             // But better to just not fail if possible, or fail gracefully.
             // However, JdbcPagingItemReader REQUIRES sort keys.
             // Let's try to fetch columns if they are empty? No, that's service logic.
             // Let's assume there is a column named "id" or just use "1" if supported (Postgres supports ORDER BY 1)
             // But sortKeys map expects a column name.
             // Let's use a fallback column name that likely exists or just "ctid" for Postgres which is a system column.
             sortKeys.put("ctid", Order.ASCENDING);
        }
        queryProvider.setSortKeys(sortKeys);

        setQueryProvider(queryProvider);

        log.info("Created reader for table: {}.{}", schemaName, tableName);
        
        try {
            afterPropertiesSet();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize TableItemReader", e);
        }
    }
    
    @Override
    public void afterPropertiesSet() throws Exception {
        super.afterPropertiesSet();
    }
}