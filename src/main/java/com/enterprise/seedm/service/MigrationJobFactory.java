package com.enterprise.seedm.service;

import com.enterprise.seedm.batch.ConstraintCreationTasklet;
import com.enterprise.seedm.batch.MongoCollectionClearTasklet;
import com.enterprise.seedm.batch.MongoItemProcessor;
import com.enterprise.seedm.batch.MongoItemReader;
import com.enterprise.seedm.batch.MongoItemWriter;
import com.enterprise.seedm.batch.TableItemProcessor;
import com.enterprise.seedm.batch.TableItemReader;
import com.enterprise.seedm.batch.TableItemWriter;
import com.enterprise.seedm.batch.TablePreparationTasklet;
import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.model.JobRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Factory to create Migration Job instances dynamically.
 * This ensures the job structure (steps) reflects the current database schema
 * at the time of execution.
 */
@Service
@Slf4j
public class MigrationJobFactory {
    private final DataSource sourceDataSource;
    private final DataSource destinationDataSource;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final PlatformTransactionManager destinationTransactionManager;
    private final TableDiscoveryService tableDiscoveryService;
    private final DestinationSchemaService destinationSchemaService;
    private final DataMaskingService dataMaskingService;
    private final SchemaConfig schemaConfig;
    private final DbConnectionService dbConnectionService;
    private final MongoConnectionHelper mongoConnectionHelper;

    @Value("${seedm.migration.chunk-size:1000}")
    private int chunkSize;

    public MigrationJobFactory(@Qualifier("sourceDataSource") DataSource sourceDataSource,
                               @Qualifier("destinationDataSource") DataSource destinationDataSource,
                               JobRepository jobRepository,
                               PlatformTransactionManager transactionManager,
                               @Qualifier("destinationTransactionManager") PlatformTransactionManager destinationTransactionManager,
                               TableDiscoveryService tableDiscoveryService,
                               DestinationSchemaService destinationSchemaService,
                               DataMaskingService dataMaskingService,
                               SchemaConfig schemaConfig,
                               DbConnectionService dbConnectionService,
                               MongoConnectionHelper mongoConnectionHelper) {
        this.sourceDataSource = sourceDataSource;
        this.destinationDataSource = destinationDataSource;
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.destinationTransactionManager = destinationTransactionManager;
        this.tableDiscoveryService = tableDiscoveryService;
        this.destinationSchemaService = destinationSchemaService;
        this.dataMaskingService = dataMaskingService;
        this.schemaConfig = schemaConfig;
        this.dbConnectionService = dbConnectionService;
        this.mongoConnectionHelper = mongoConnectionHelper;
    }

    /**
     * Creates a new migration job based on current DB schema.
     */
    @SuppressWarnings("unchecked")
    public Job createMigrationJob(JobRequest jobRequest) throws SQLException, JsonProcessingException {
        log.info("Creating dynamic migration job...");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> configDetails =jobRequest.getConfigDetails();
        Map<String, Object> rulesConfig = (Map<String, Object>) configDetails.get("rules");
        List<String> tables = (List<String>) rulesConfig.get("targetTables");

        if (tables == null || tables.isEmpty()) {
            log.info("No target tables specified, discovering all tables from source schema...");
            tables = tableDiscoveryService.discoverTables();
        }

        if (tables.isEmpty()) {
            log.warn("No tables found in source schema: {}", schemaConfig.getSourceSchema());
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
            // Create a dummy step if no tables found to avoid job failure
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

    @SuppressWarnings("unchecked")
    public Job createMongoMigrationJob(JobRequest jobRequest) throws JsonProcessingException {
        log.info("Creating dynamic Mongo migration job...");
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> configDetails = jobRequest.getConfigDetails();
        Map<String, Object> sourceConfig = (Map<String, Object>) configDetails.get("source");
        Map<String, Object> destConfig = (Map<String, Object>) configDetails.get("dest");
        Map<String, Object> rulesConfig = (Map<String, Object>) configDetails.get("rules");

        Long sourceConnectionId = Long.parseLong(sourceConfig.get("id").toString());
        String sourceDatabaseName = sourceConfig.get("schema").toString();
        Long destConnectionId = Long.parseLong(destConfig.get("id").toString());
        String destDatabaseName = destConfig.get("schema").toString();

        List<String> collections = (List<String>) rulesConfig.get("targetTables");
        if (collections == null) collections = new ArrayList<>();

        MongoClient sourceClient = mongoConnectionHelper.createClient(sourceConnectionId);
        MongoClient destClient = mongoConnectionHelper.createClient(destConnectionId);

        JobBuilder jobBuilder = new JobBuilder("DBMigrationJob", jobRepository)
                .incrementer(new RunIdIncrementer());

        SimpleJobBuilder simpleJobBuilder = null;

        for (String collectionName : collections) {
            // Step 1: Clear collection
            Step clearStep = new StepBuilder("prepare_" + collectionName, jobRepository)
                    .tasklet(new MongoCollectionClearTasklet(destClient, destDatabaseName, collectionName), transactionManager)
                    .build();

            // Step 2: Migrate
            MongoItemReader reader = new MongoItemReader(sourceClient, sourceDatabaseName, collectionName);
            MongoItemProcessor processor = new MongoItemProcessor(collectionName, dataMaskingService); // ADDED
            MongoItemWriter writer = new MongoItemWriter(destClient, destDatabaseName, collectionName);

            Step migrateStep = new StepBuilder("migrate_" + collectionName, jobRepository)
                    .<Document, Document>chunk(chunkSize, transactionManager)
                    .reader(reader)
                    .processor(processor) // ADDED
                    .writer(writer)
                    .build();

            if (simpleJobBuilder == null) {
                simpleJobBuilder = jobBuilder.start(clearStep);
            } else {
                simpleJobBuilder.next(clearStep);
            }
            simpleJobBuilder.next(migrateStep);
        }

        if (simpleJobBuilder == null) {
            Step noTablesStep = new StepBuilder("noTablesStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        log.info("No collections to migrate");
                        return null;
                    }, transactionManager)
                    .build();
            simpleJobBuilder = jobBuilder.start(noTablesStep);
        }

        return simpleJobBuilder.build();
    }

    private Step createTablePreparationStep(String tableName) {
        // Get detailed column metadata for this table
        List<ColumnMetadata> columnMetadata = tableDiscoveryService.getTableColumnMetadata(tableName);
        
        return new StepBuilder("prepare_" + tableName, jobRepository)
                .tasklet(new TablePreparationTasklet(destinationSchemaService, tableName, columnMetadata), transactionManager)
                .build();
    }

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
                schemaConfig.getSourceSchema(),
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
                schemaConfig.getDestinationSchema(),
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

    private Step createConstraintCreationStep() {
        return new StepBuilder("createConstraintsStep", jobRepository)
                .tasklet(new ConstraintCreationTasklet(tableDiscoveryService, destinationSchemaService), transactionManager)
                .build();
    }
}