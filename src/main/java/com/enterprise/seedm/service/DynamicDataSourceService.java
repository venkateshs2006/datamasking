package com.enterprise.seedm.service;

import com.enterprise.seedm.config.SwappableDataSource;
import com.enterprise.seedm.model.DbConnectionRequest;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

@Service
@Slf4j
public class DynamicDataSourceService {

    private final SwappableDataSource sourceDataSource;
    private final SwappableDataSource destinationDataSource;

    // We need to update the schema property in the application context or service
    // Since @Value is injected at startup, we need a way to update it dynamically for the services that use it.
    // For now, let's assume we update the datasource, and the schema is passed or updated in a shared config bean.
    // But TableDiscoveryService uses @Value. We might need to make TableDiscoveryService schema-aware or update it.
    
    // Actually, TableDiscoveryService and DestinationSchemaService use @Value("${migration.source.schema}")
    // We need to make these services updateable or fetch schema from a dynamic source.
    // Let's introduce a SchemaConfig bean.
    
    private final SchemaConfig schemaConfig;

    public DynamicDataSourceService(@Qualifier("sourceDataSource") SwappableDataSource sourceDataSource,
                                    @Qualifier("destinationDataSource") SwappableDataSource destinationDataSource,
                                    SchemaConfig schemaConfig) {
        this.sourceDataSource = sourceDataSource;
        this.destinationDataSource = destinationDataSource;
        this.schemaConfig = schemaConfig;
    }

    public List<String> fetchSchemas(DbConnectionRequest request) {
        // Create a temporary datasource to fetch schemas
        DataSource tempDs = DataSourceBuilder.create()
                .url(request.getUrl())
                .username(request.getUsername())
                .password(request.getPassword())
                .driverClassName("org.postgresql.Driver") // Assuming Postgres for now
                .type(HikariDataSource.class)
                .build();
        
        JdbcTemplate jdbcTemplate = new JdbcTemplate(tempDs);
        List<String> schemas = jdbcTemplate.queryForList(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN ('information_schema', 'pg_catalog') ORDER BY schema_name", 
                String.class);
        
        if (tempDs instanceof HikariDataSource) {
            ((HikariDataSource) tempDs).close();
        }
        
        return schemas;
    }

    public void updateConnection(DbConnectionRequest request) {
        log.info("Updating {} connection to {}", request.getType(), request.getUrl());
        
        HikariDataSource newDataSource = (HikariDataSource) DataSourceBuilder.create()
                .url(request.getUrl())
                .username(request.getUsername())
                .password(request.getPassword())
                .driverClassName("org.postgresql.Driver")
                .type(HikariDataSource.class)
                .build();
        
        // Configure pool settings similar to original
        newDataSource.setMaximumPoolSize(10);
        newDataSource.setMinimumIdle(2);

        if ("source".equalsIgnoreCase(request.getType())) {
            sourceDataSource.setTargetDataSource(newDataSource);
            schemaConfig.setSourceSchema(request.getSchema());
        } else if ("destination".equalsIgnoreCase(request.getType())) {
            destinationDataSource.setTargetDataSource(newDataSource);
            schemaConfig.setDestinationSchema(request.getSchema());
        } else {
            throw new IllegalArgumentException("Invalid connection type: " + request.getType());
        }
        
        log.info("Successfully updated {} datasource and schema to {}", request.getType(), request.getSchema());
    }
}
