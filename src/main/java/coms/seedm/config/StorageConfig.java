package coms.seedm.config;

import jakarta.validation.constraints.NotBlank;

/**
 * Describes where masked output is written.
 * type: "local" is implemented out of the box. The abstraction (see
 * com.seedm.service.StorageWriter) makes it straightforward to add "s3", "gcs", etc.
 * format (optional, defaults to "sql"): "sql" writes plain INSERT statements,
 * "sql-gz" gzips the same output, "bin" writes a compact Java-serialized form.
 */
public class StorageConfig {

    @NotBlank
    private String type;

    private String id;

    @NotBlank
    private String path;

    private String format = "sql";

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getFormat() {
        return format == null || format.isBlank() ? "sql" : format.toLowerCase();
    }

    public void setFormat(String format) {
        this.format = format;
    }

    @Override
    public String toString() {
        return "StorageConfig{type='" + type + "', path='" + path + "', format='" + getFormat() + "'}";
    }
}
