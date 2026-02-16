package com.enterprise.seedm.batch;

import com.enterprise.seedm.model.ColumnMetadata;
import com.enterprise.seedm.service.DestinationSchemaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.List;

/**
 * Tasklet to recreate the destination table (Drop & Create)
 * This ensures the table exists and is empty before migration,
 * and removes constraints to speed up insertion.
 */
@Slf4j
public class TablePreparationTasklet implements Tasklet {

    private final DestinationSchemaService destinationSchemaService;
    private final String tableName;
    private final List<ColumnMetadata> columnMetadata;

    public TablePreparationTasklet(DestinationSchemaService destinationSchemaService,
                                   String tableName,
                                   List<ColumnMetadata> columnMetadata) {
        this.destinationSchemaService = destinationSchemaService;
        this.tableName = tableName;
        this.columnMetadata = columnMetadata;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        log.info("Preparing table {} for migration...", tableName);
        destinationSchemaService.recreateTable(tableName, columnMetadata);
        return RepeatStatus.FINISHED;
    }
}
