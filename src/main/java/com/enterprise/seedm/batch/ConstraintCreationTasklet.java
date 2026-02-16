package com.enterprise.seedm.batch;

import com.enterprise.seedm.model.ConstraintMetadata;
import com.enterprise.seedm.service.DestinationSchemaService;
import com.enterprise.seedm.service.TableDiscoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.List;

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

        for (String tableName : tables) {
            log.info("Processing constraints for table: {}", tableName);
            
            // Fetch constraints from source
            List<ConstraintMetadata> constraints = tableDiscoveryService.getTableConstraints(tableName);
            
            if (!constraints.isEmpty()) {
                // Apply constraints to destination
                destinationSchemaService.createConstraints(tableName, constraints);
            } else {
                log.debug("No constraints found for table: {}", tableName);
            }
        }

        log.info("Constraint creation task completed.");
        return RepeatStatus.FINISHED;
    }
}
