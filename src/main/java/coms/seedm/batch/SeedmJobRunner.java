package coms.seedm.batch;

import com.seedm.config.RulesConfig;
import com.seedm.config.SeedmRequest;
import com.seedm.masking.ColumnClassifier;
import com.seedm.masking.MaskingService;
import com.seedm.model.TableMetadata;
import com.seedm.service.SourceDataSourceFactory;
import com.seedm.service.TableMetadataService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Assembles a Spring Batch Job at request time: one step per entry in
 * rules.targetTables, run sequentially in the order given (so INSERTs land in
 * a FK-safe order when the caller lists parent tables before children).
 *
 * Steps are built programmatically rather than as @Bean definitions because
 * the table list, source connection, and masking rules are only known once a
 * request arrives.
 */
@Service
public class SeedmJobRunner {

    private static final int CHUNK_SIZE = 500;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    // private final JobLauncher jobLauncher;
    private final SourceDataSourceFactory dataSourceFactory;
    private final TableMetadataService tableMetadataService;
    private final ColumnClassifier columnClassifier;
    private final MaskingService maskingService;

    public SeedmJobRunner(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JobLauncher jobLauncher,
            SourceDataSourceFactory dataSourceFactory,
            TableMetadataService tableMetadataService,
            ColumnClassifier columnClassifier,
            MaskingService maskingService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        // this.jobLauncher = jobLauncher;
        this.dataSourceFactory = dataSourceFactory;
        this.tableMetadataService = tableMetadataService;
        this.columnClassifier = columnClassifier;
        this.maskingService = maskingService;
    }

    public JobExecution run(SeedmRequest request) throws Exception {
        RulesConfig rules = request.getRules();
        DataSource sourceDataSource = dataSourceFactory.build(request.getSource());
        String schema = request.getSource().getSchema();

        JobBuilder jobBuilder = new JobBuilder(
                "seedm-backup-" + request.getSource().getId() + "-" + System.currentTimeMillis(),
                jobRepository);

        org.springframework.batch.core.job.builder.SimpleJobBuilder simpleJobBuilder = null;

        for (String tableName : rules.getTargetTables()) {
            TableMetadata tableMetadata = tableMetadataService.readMetadata(sourceDataSource, schema, tableName);

            var reader = buildReader(sourceDataSource, tableMetadata);
            var processor = new MaskingItemProcessor(tableName, tableMetadata, rules, columnClassifier, maskingService);
            var writer = buildWriter(request, tableMetadata);

            org.springframework.batch.core.Step step = new StepBuilder("mask-" + tableName, jobRepository)
                    .<Map<String, Object>, Map<String, Object>>chunk(CHUNK_SIZE, transactionManager)
                    .reader(reader)
                    .processor(processor)
                    .writer(writer)
                    .build();

            simpleJobBuilder = (simpleJobBuilder == null) ? jobBuilder.start(step) : simpleJobBuilder.next(step);
        }

        if (simpleJobBuilder == null) {
            throw new IllegalArgumentException("rules.targetTables must contain at least one table");
        }

        Job job = simpleJobBuilder.build();

        var jobParameters = new JobParametersBuilder()
                .addString("sourceId", request.getSource().getId())
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters();

        return jobLauncher.run(job, jobParameters);
    }

    private JdbcCursorItemReader<Map<String, Object>> buildReader(DataSource dataSource, TableMetadata tableMetadata) {
        return new JdbcCursorItemReaderBuilder<Map<String, Object>>()
                .name("reader-" + tableMetadata.getTableName())
                .dataSource(dataSource)
                .sql("SELECT * FROM " + tableMetadata.qualifiedName())
                .rowMapper(new ColumnMapRowMapper())
                .build();
    }

    /**
     * Picks the output writer based on storage.format ("bin" -> binary, else SQL
     * text/gzip).
     */
    private org.springframework.batch.item.ItemStreamWriter<Map<String, Object>> buildWriter(
            SeedmRequest request, TableMetadata tableMetadata) {
        if ("bin".equals(request.getStorage().getFormat())) {
            return new BinaryItemWriter(request.getStorage(), tableMetadata);
        }
        return new SqlInsertItemWriter(request.getStorage(), tableMetadata);
    }
}
