package com.enterprise.seedm.config;


import com.enterprise.seedm.batch.ConstraintCreationTasklet;
import com.enterprise.seedm.batch.TableItemProcessor;
import com.enterprise.seedm.batch.TableItemReader;
import com.enterprise.seedm.batch.TableItemWriter;
import com.enterprise.seedm.batch.TablePreparationTasklet;
import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.service.DataMaskingService;
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
    private final DataMaskingService dataMaskingService;

    @Value("${seedm.migration.source.schema}")
    private String sourceSchema;

    @Value("${seedm.migration.destination.schema}")
    private String destinationSchema;

    @Value("${seedm.migration.chunk-size:1000}")
    private int chunkSize;

    public BatchJobConfig(@Qualifier("sourceDataSource") DataSource sourceDataSource,
                          @Qualifier("destinationDataSource") DataSource destinationDataSource,
                          JobRepository jobRepository,
                          PlatformTransactionManager transactionManager,
                          @Qualifier("destinationTransactionManager") PlatformTransactionManager destinationTransactionManager,
                          TableDiscoveryService tableDiscoveryService,
                          DestinationSchemaService destinationSchemaService,
                          DataMaskingService dataMaskingService) {
        this.sourceDataSource = sourceDataSource;
        this.destinationDataSource = destinationDataSource;
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.destinationTransactionManager = destinationTransactionManager;
        this.tableDiscoveryService = tableDiscoveryService;
        this.destinationSchemaService = destinationSchemaService;
        this.dataMaskingService = dataMaskingService;
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
        JobBuilder jobBuilder = new JobBuilder("DBMigrationJob", jobRepository)
                .incrementer(new RunIdIncrementer());

        SimpleJobBuilder simpleJobBuilder = null;

        // 1. Create steps for each table migration
        for (String tableName : tables) {
            // Step 1.1: Prepare table (Drop & Create without constraints)
            Step prepareStep = createTablePreparationStep(tableName);
            
            // Step 1.2: Migrate data (Read -> Mask -> Write)
            Step migrateStep = createTableMigrationStep(tableName);

            if (simpleJobBuilder == null) {
                simpleJobBuilder = jobBuilder.start(prepareStep);
            } else {
                simpleJobBuilder.next(prepareStep);
            }
            simpleJobBuilder.next(migrateStep);
        }

        // 2. Create a final step for constraint creation (PK, FK, Unique)
        Step constraintStep = createConstraintCreationStep();

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
        } else {
            // Add constraint step at the end
            simpleJobBuilder.next(constraintStep);
        }

        return simpleJobBuilder.build();
    }

    /**
     * Create a step for preparing a single table (Drop & Create)
     */
    private Step createTablePreparationStep(String tableName) {
        // Get detailed column metadata for this table
        List<ColumnMetadata> columnMetadata = tableDiscoveryService.getTableColumnMetadata(tableName);
        
        return new StepBuilder("prepare_" + tableName, jobRepository)
                .tasklet(new TablePreparationTasklet(destinationSchemaService, tableName, columnMetadata), transactionManager)
                .build();
    }

    /**
     * Create a step for migrating a single table (Data only)
     */
    private Step createTableMigrationStep(String tableName) {
        log.info("Creating migration step for table: {}", tableName);

        // Get detailed column metadata for this table
        List<ColumnMetadata> columnMetadata = tableDiscoveryService.getTableColumnMetadata(tableName);
        
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

        // Create processor
        TableItemProcessor processor = new TableItemProcessor(tableName, dataMaskingService);

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
                .processor(processor)
                .writer(writer)
                .build();
    }

    /**
     * Create a step for creating constraints after data migration
     */
    private Step createConstraintCreationStep() {
        return new StepBuilder("createConstraintsStep", jobRepository)
                .tasklet(new ConstraintCreationTasklet(tableDiscoveryService, destinationSchemaService), transactionManager)
                .build();
    }
}
