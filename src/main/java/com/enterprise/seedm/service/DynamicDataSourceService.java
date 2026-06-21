package com.enterprise.seedm.service;

import com.enterprise.seedm.config.SwappableDataSource;
import com.enterprise.seedm.model.DbConnectionRequest;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class DynamicDataSourceService {

    private final SwappableDataSource sourceDataSource;
    private final SwappableDataSource destinationDataSource;
    private final SchemaConfig schemaConfig;
    private final VaultService vaultService;

    public DynamicDataSourceService(@Qualifier("sourceDataSource") SwappableDataSource sourceDataSource,
                                    @Qualifier("destinationDataSource") SwappableDataSource destinationDataSource,
                                    SchemaConfig schemaConfig,
                                    VaultService vaultService) {
        this.sourceDataSource = sourceDataSource;
        this.destinationDataSource = destinationDataSource;
        this.schemaConfig = schemaConfig;
        this.vaultService = vaultService;
    }

    private void resolveVaultCredentials(DbConnectionRequest request) {
        if (StringUtils.hasText(request.getVaultPath())) {
            String path = request.getVaultPath();
            if (StringUtils.hasText(request.getVaultRole())) {
                path = path + "/" + request.getVaultRole();
            }
            Map<String, Object> credentials = vaultService.getDatabaseCredentials(path);
            if (credentials != null) {
                // For DB secrets engine, user/pass are often directly in data
                if (credentials.containsKey("username")) request.setUsername((String) credentials.get("username"));
                if (credentials.containsKey("password")) request.setPassword((String) credentials.get("password"));
                // Also check for a nested 'data' block for KV v2 secrets
                if (credentials.containsKey("data") && credentials.get("data") instanceof Map) {
                    Map<String, Object> nestedData = (Map<String, Object>) credentials.get("data");
                    if (nestedData.containsKey("url")) request.setUrl((String) nestedData.get("url"));
                    if (nestedData.containsKey("username")) request.setUsername((String) nestedData.get("username"));
                    if (nestedData.containsKey("password")) request.setPassword((String) nestedData.get("password"));
                }
            }
        }
    }

    public List<String> fetchSchemas(DbConnectionRequest request) {
        resolveVaultCredentials(request);
        
        DataSource tempDs = DataSourceBuilder.create()
                .url(request.getUrl())
                .username(request.getUsername())
                .password(request.getPassword())
                .driverClassName("org.postgresql.Driver") 
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

    public void createSchema(DbConnectionRequest request) {
        log.info("Creating schema {} for {} connection", request.getSchema(), request.getType());
        
        resolveVaultCredentials(request);
        
        DataSource tempDs = DataSourceBuilder.create()
                .url(request.getUrl())
                .username(request.getUsername())
                .password(request.getPassword())
                .driverClassName("org.postgresql.Driver")
                .type(HikariDataSource.class)
                .build();
        
        JdbcTemplate jdbcTemplate = new JdbcTemplate(tempDs);
        try {
            if (request.getSchema() != null && request.getSchema().matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + request.getSchema());
                log.info("Schema {} created successfully.", request.getSchema());
            } else {
                throw new IllegalArgumentException("Invalid schema name. Must contain only alphanumeric characters and underscores.");
            }
        } finally {
            if (tempDs instanceof HikariDataSource) {
                ((HikariDataSource) tempDs).close();
            }
        }
    }

    public void updateConnection(DbConnectionRequest request) {
        log.info("Updating {} connection to {}", request.getType(), request.getUrl());
        
        resolveVaultCredentials(request);
        
        HikariDataSource newDataSource = (HikariDataSource) DataSourceBuilder.create()
                .url(request.getUrl())
                .username(request.getUsername())
                .password(request.getPassword())
                .driverClassName("org.postgresql.Driver")
                .type(HikariDataSource.class)
                .build();
        
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