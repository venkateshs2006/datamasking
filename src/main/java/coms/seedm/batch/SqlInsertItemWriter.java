package coms.seedm.batch;

import com.seedm.config.StorageConfig;
import com.seedm.model.ColumnMetadata;
import com.seedm.model.TableMetadata;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes each processed row as a plain "INSERT INTO schema.table (...) VALUES (...);"
 * statement. One file per table is created under storage.path.
 * storage.format = "sql-gz" gzips the output; anything else writes plain text.
 */
public class SqlInsertItemWriter implements ItemStreamWriter<Map<String, Object>> {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StorageConfig storage;
    private final TableMetadata tableMetadata;
    private final String insertPrefix;

    private Writer writer;
    private Path outputFile;

    public SqlInsertItemWriter(StorageConfig storage, TableMetadata tableMetadata) {
        this.storage = storage;
        this.tableMetadata = tableMetadata;

        String columnList = tableMetadata.getColumns().stream()
                .map(ColumnMetadata::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        this.insertPrefix = "INSERT INTO " + tableMetadata.qualifiedName() + " (" + columnList + ") VALUES (";
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            Path dir = Path.of(storage.getPath());
            Files.createDirectories(dir);

            boolean gzip = "sql-gz".equals(storage.getFormat());
            String extension = gzip ? ".sql.gz" : ".sql";
            outputFile = dir.resolve(tableMetadata.getSchema() + "_" + tableMetadata.getTableName() + extension);

            var outputStream = Files.newOutputStream(outputFile);
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    gzip ? new GZIPOutputStream(outputStream) : outputStream, StandardCharsets.UTF_8));

            writer.write("-- seedm export: " + tableMetadata.qualifiedName() + "\n");
            writer.write("-- generated " + java.time.Instant.now() + "\n\n");
        } catch (IOException e) {
            throw new ItemStreamException("Unable to open output file for table " + tableMetadata.qualifiedName(), e);
        }
    }

    @Override
    public void write(Chunk<? extends Map<String, Object>> chunk) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : chunk) {
            sb.append(insertPrefix);
            boolean first = true;
            for (ColumnMetadata column : tableMetadata.getColumns()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(formatLiteral(row.get(column.getName())));
            }
            sb.append(");\n");
        }
        writer.write(sb.toString());
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new ItemStreamException("Unable to flush output for " + tableMetadata.qualifiedName(), e);
        }
    }

    @Override
    public void close() throws ItemStreamException {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (IOException e) {
            throw new ItemStreamException("Unable to close output for " + tableMetadata.qualifiedName(), e);
        }
    }

    private String formatLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof java.sql.Timestamp ts) {
            return "'" + ts.toLocalDateTime().format(TS_FORMAT) + "'";
        }
        if (value instanceof java.sql.Date || value instanceof java.time.LocalDate
                || value instanceof java.time.LocalDateTime || value instanceof java.sql.Time) {
            return "'" + escape(value.toString()) + "'";
        }
        if (value instanceof byte[] bytes) {
            return "'" + java.util.Base64.getEncoder().encodeToString(bytes) + "'";
        }
        return "'" + escape(value.toString()) + "'";
    }

    private String escape(String value) {
        return value.replace("'", "''");
    }

    public Path getOutputFile() {
        return outputFile;
    }
}
