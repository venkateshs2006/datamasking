package com.enterprise.seedm.config;


import com.enterprise.seedm.batch.TableItemReader;
import com.enterprise.seedm.batch.TableItemWriter;
import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.service.DestinationSchemaService;
import com.enterprise.seedm.service.TableDiscoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Batch Job Configuration
 * Configures Spring Batch job to migrate all tables
 */
@Configuration
@Slf4j
public class BatchJobConfig {
    private final DataSource sourceDataSource;
    private final DataSource destinationDataSource;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final PlatformTransactionManager destinationTransactionManager;
    private final TableDiscoveryService tableDiscoveryService;
    private final DestinationSchemaService destinationSchemaService;

    @Value("${migration.source.schema}")
    private String sourceSchema;

    @Value("${migration.destination.schema}")
    private String destinationSchema;

    @Value("${migration.chunk-size:1000}")
    private int chunkSize;

    public BatchJobConfig(@Qualifier("sourceDataSource") DataSource sourceDataSource,
                          @Qualifier("destinationDataSource") DataSource destinationDataSource,
                          JobRepository jobRepository,
                          PlatformTransactionManager transactionManager,
                          @Qualifier("destinationTransactionManager") PlatformTransactionManager destinationTransactionManager,
                          TableDiscoveryService tableDiscoveryService,
                          DestinationSchemaService destinationSchemaService) {
        this.sourceDataSource = sourceDataSource;
        this.destinationDataSource = destinationDataSource;
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.destinationTransactionManager = destinationTransactionManager;
        this.tableDiscoveryService = tableDiscoveryService;
        this.destinationSchemaService = destinationSchemaService;
    }

    /**
     * Main migration job
     */
    @Bean
    public Job migrationJob() throws SQLException {
        log.info("Creating migration job...");

        // Discover all tables from source schema
        List<String> tables = tableDiscoveryService.discoverTables();

        if (tables.isEmpty()) {
            log.warn("No tables found in source schema: {}", sourceSchema);
        }

        // Create job builder
        JobBuilder jobBuilder = new JobBuilder("schemaMigrationJob", jobRepository)
                .incrementer(new RunIdIncrementer());

        SimpleJobBuilder simpleJobBuilder = null;

        for (String tableName : tables) {
            Step step = createTableMigrationStep(tableName);

            if (simpleJobBuilder == null) {
                simpleJobBuilder = jobBuilder.start(step);
            } else {
                simpleJobBuilder.next(step);
            }
        }

        if (simpleJobBuilder == null) {
            log.error("No steps created - no tables to migrate!");
            // Create a dummy step
            Step noTablesStep = new StepBuilder("noTablesStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        log.info("No tables to migrate");
                        return null;
                    }, transactionManager)
                    .build();
            simpleJobBuilder = jobBuilder.start(noTablesStep);
        }

        return simpleJobBuilder.build();
    }

    /**
     * Create a step for migrating a single table
     */
    private Step createTableMigrationStep(String tableName) {
        log.info("Creating step for table: {}", tableName);

        // Get detailed column metadata for this table
        List<ColumnMetadata> columnMetadata = tableDiscoveryService.getTableColumnMetadata(tableName);
        
        // Recreate table in destination
        destinationSchemaService.recreateTable(tableName, columnMetadata);

        // Extract column names for reader
        List<String> columns = columnMetadata.stream()
                .map(ColumnMetadata::getColumnName)
                .collect(Collectors.toList());

        // Create reader
        TableItemReader reader = new TableItemReader(
                sourceDataSource,
                sourceSchema,
                tableName,
                columns,
                chunkSize
        );
        reader.setName(tableName + "Reader");

        // Create writer
        TableItemWriter writer = new TableItemWriter(
                destinationDataSource,
                destinationSchema,
                tableName
        );

        // Build step
        return new StepBuilder("migrate_" + tableName, jobRepository)
                .<Map<String, Object>, Map<String, Object>>chunk(chunkSize, destinationTransactionManager)
                .reader(reader)
                .writer(writer)
                .build();
    }
}
