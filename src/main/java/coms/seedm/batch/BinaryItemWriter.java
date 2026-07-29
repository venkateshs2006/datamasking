package coms.seedm.batch;

import com.seedm.config.StorageConfig;
import com.seedm.model.TableMetadata;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes masked rows as a sequence of Java-serialized LinkedHashMap objects,
 * one per row, for storage.format = "bin". This is a simple, dependency-free
 * binary format meant as a starting point - swap for Avro/Parquet/Protobuf if
 * you need cross-language portability or a real columnar format.
 */
public class BinaryItemWriter implements ItemStreamWriter<Map<String, Object>> {

    private final StorageConfig storage;
    private final TableMetadata tableMetadata;
    private ObjectOutputStream objectOut;
    private Path outputFile;

    public BinaryItemWriter(StorageConfig storage, TableMetadata tableMetadata) {
        this.storage = storage;
        this.tableMetadata = tableMetadata;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        try {
            Path dir = Path.of(storage.getPath());
            Files.createDirectories(dir);
            outputFile = dir.resolve(tableMetadata.getSchema() + "_" + tableMetadata.getTableName() + ".bin");
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputFile));
            objectOut = new ObjectOutputStream(out);
        } catch (IOException e) {
            throw new ItemStreamException("Unable to open binary output for " + tableMetadata.qualifiedName(), e);
        }
    }

    @Override
    public void write(Chunk<? extends Map<String, Object>> chunk) throws Exception {
        for (Map<String, Object> row : chunk) {
            objectOut.writeObject(new HashMap<>(row));
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        try {
            objectOut.flush();
        } catch (IOException e) {
            throw new ItemStreamException("Unable to flush binary output for " + tableMetadata.qualifiedName(), e);
        }
    }

    @Override
    public void close() throws ItemStreamException {
        try {
            if (objectOut != null) {
                objectOut.flush();
                objectOut.close();
            }
        } catch (IOException e) {
            throw new ItemStreamException("Unable to close binary output for " + tableMetadata.qualifiedName(), e);
        }
    }

    public Path getOutputFile() {
        return outputFile;
    }
}
