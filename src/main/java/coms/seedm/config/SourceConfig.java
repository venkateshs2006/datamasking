package coms.seedm.config;

import jakarta.validation.constraints.NotBlank;

/**
 * Describes the source database to read from.
 *
 * "url" in the sample payload is a human-labelled string like:
 *   "Finanace DB (jdbc:postgresql://localhost:5432/BankDB)"
 * The real JDBC URL is extracted out of the parentheses at runtime
 * (see {@link com.seedm.util.JdbcUrlParser}).
 *
 * NOTE: the sample payload does not include DB credentials. Since credentials
 * should never be hard-coded into a masking-rules JSON that may be checked into
 * source control, username/password are optional here and are resolved with
 * this fallback order at connection time:
 *   1. source.username / source.password if present in the JSON
 *   2. environment variables SEEDM_DB_USER_{source.id} / SEEDM_DB_PASS_{source.id}
 *   3. environment variables SEEDM_DB_USER / SEEDM_DB_PASS
 */
public class SourceConfig {

    @NotBlank
    private String id;

    @NotBlank
    private String url;

    @NotBlank
    private String schema;

    /** Optional. See class javadoc for fallback resolution order. */
    private String username;

    /** Optional. See class javadoc for fallback resolution order. */
    private String password;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "SourceConfig{id='" + id + "', url='" + url + "', schema='" + schema + "'}";
    }
}
