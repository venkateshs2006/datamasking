package com.enterprise.seedm.config;

import com.enterprise.seedm.service.MigrationJobFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;

/**
 * Batch Job Configuration
 * Configures Spring Batch job to migrate all tables
 */
@Configuration
@Slf4j
public class BatchJobConfig {

    private final MigrationJobFactory migrationJobFactory;

    public BatchJobConfig(MigrationJobFactory migrationJobFactory) {
        this.migrationJobFactory = migrationJobFactory;
    }

    /**
     * Main migration job
     * Note: This bean is created at startup. If the DB connection changes,
     * this bean might hold stale steps if not re-created or if steps are not dynamic.
     * However, since we are using a SwappableDataSource and dynamic SchemaConfig,
     * the steps created here will use those references.
     * BUT, the list of tables (steps) is determined at creation time.
     * To support dynamic table lists after connection change, we should probably
     * not define the Job as a singleton bean here, or use a JobFactory.
     * 
     * For now, let's keep it simple. If the user changes the DB, they might need to restart 
     * or we need a way to refresh this bean.
     * 
     * BETTER APPROACH: The controller should ask the factory for a NEW job instance 
     * every time "Start Migration" is clicked, instead of injecting a singleton Job.
     * 
     * So, we will REMOVE the @Bean definition for the Job here, and let the Controller
     * call migrationJobFactory.createMigrationJob() directly.
     */
    
    // Removing the singleton Job bean to allow dynamic creation based on current DB state.
    // @Bean
    // public Job migrationJob() throws SQLException {
    //     return migrationJobFactory.createMigrationJob();
    // }
}
