package com.enterprise.seedm.config;


import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Database Configuration
 * Configures source and destination PostgreSQL datasources
 */
@Configuration
public class DatabaseConfig {

    // Source Database Configuration
    @Bean
    @ConfigurationProperties("spring.datasource.batchdb")
    public DataSourceProperties batchDataSourceProperties() {
        return new DataSourceProperties();
    }
    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource() {
        return batchDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.source")
    public DataSourceProperties sourceDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "sourceDataSource")
    public DataSource sourceDataSource() {
        return sourceDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    // Destination Database Configuration

    @Bean
    @ConfigurationProperties("spring.datasource.destination")
    public DataSourceProperties destinationDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "destinationDataSource")
    public DataSource destinationDataSource() {
        return destinationDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
