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
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.batchdb")
    public DataSourceProperties batchDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(@Qualifier("batchDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "sourceDataSource")
    public SwappableDataSource sourceDataSource() {
        return new SwappableDataSource();
    }

    @Bean(name = "sourceTransactionManager")
    public PlatformTransactionManager sourceTransactionManager(@Qualifier("sourceDataSource") DataSource sourceDataSource) {
        return new DataSourceTransactionManager(sourceDataSource);
    }

    @Bean(name = "destinationDataSource")
    public SwappableDataSource destinationDataSource() {
        return new SwappableDataSource();
    }

    @Bean(name = "destinationTransactionManager")
    public PlatformTransactionManager destinationTransactionManager(@Qualifier("destinationDataSource") DataSource destinationDataSource) {
        return new DataSourceTransactionManager(destinationDataSource);
    }
}