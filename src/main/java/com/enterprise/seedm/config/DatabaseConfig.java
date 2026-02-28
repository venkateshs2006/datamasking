package com.enterprise.seedm.config;


import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

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
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @ConfigurationProperties("spring.datasource.source")
    public DataSourceProperties sourceDataSourceProperties() {
        return new DataSourceProperties();
    }

    // Wrap the actual source datasource in a SwappableDataSource
    @Bean(name = "sourceDataSource")
    public SwappableDataSource sourceDataSource() {
        DataSource initialDataSource = sourceDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return new SwappableDataSource(initialDataSource);
    }

    @Bean(name = "sourceTransactionManager")
    public PlatformTransactionManager sourceTransactionManager(@Qualifier("sourceDataSource") DataSource sourceDataSource) {
        return new DataSourceTransactionManager(sourceDataSource);
    }

    // Destination Database Configuration

    @Bean
    @ConfigurationProperties("spring.datasource.destination")
    public DataSourceProperties destinationDataSourceProperties() {
        return new DataSourceProperties();
    }

    // Wrap the actual destination datasource in a SwappableDataSource
    @Bean(name = "destinationDataSource")
    public SwappableDataSource destinationDataSource() {
        DataSource initialDataSource = destinationDataSourceProperties()
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        return new SwappableDataSource(initialDataSource);
    }

    @Bean(name = "destinationTransactionManager")
    public PlatformTransactionManager destinationTransactionManager(@Qualifier("destinationDataSource") DataSource destinationDataSource) {
        return new DataSourceTransactionManager(destinationDataSource);
    }
}
