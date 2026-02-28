package com.enterprise.seedm.batch;

import com.enterprise.seedm.model.ConstraintMetadata;
import com.enterprise.seedm.service.DestinationSchemaService;
import com.enterprise.seedm.service.TableDiscoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tasklet to create constraints (PK, FK, Unique) on destination tables
 * This should run after all data migration steps are complete
 */
@Slf4j
public class ConstraintCreationTasklet implements Tasklet {

    private final TableDiscoveryService tableDiscoveryService;
    private final DestinationSchemaService destinationSchemaService;

    public ConstraintCreationTasklet(TableDiscoveryService tableDiscoveryService,
                                     DestinationSchemaService destinationSchemaService) {
        this.tableDiscoveryService = tableDiscoveryService;
        this.destinationSchemaService = destinationSchemaService;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        log.info("Starting constraint creation task...");

        List<String> tables = tableDiscoveryService.discoverTables();
        
        // 1. First, create all Primary Keys and Unique Constraints for all tables
        // This ensures that when we create Foreign Keys later, the referenced unique/PK constraints exist.
        for (String tableName : tables) {
            log.info("Creating PK/Unique constraints for table: {}", tableName);
            List<ConstraintMetadata> allConstraints = tableDiscoveryService.getTableConstraints(tableName);
            
            List<ConstraintMetadata> pkAndUnique = allConstraints.stream()
                    .filter(c -> "PRIMARY KEY".equals(c.getConstraintType()) || "UNIQUE".equals(c.getConstraintType()))
                    .collect(Collectors.toList());
            
            if (!pkAndUnique.isEmpty()) {
                destinationSchemaService.createConstraints(tableName, pkAndUnique);
            }
        }

        // 2. Then, create Foreign Keys
        for (String tableName : tables) {
            log.info("Creating Foreign Keys for table: {}", tableName);
            List<ConstraintMetadata> allConstraints = tableDiscoveryService.getTableConstraints(tableName);
            
            List<ConstraintMetadata> foreignKeys = allConstraints.stream()
                    .filter(c -> "FOREIGN KEY".equals(c.getConstraintType()))
                    .collect(Collectors.toList());
            
            if (!foreignKeys.isEmpty()) {
                destinationSchemaService.createConstraints(tableName, foreignKeys);
            }
        }

        log.info("Constraint creation task completed.");
        return RepeatStatus.FINISHED;
    }
}
