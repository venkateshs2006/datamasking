package coms.seedm.service;

import com.seedm.config.SourceConfig;
import com.seedm.util.JdbcUrlParser;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Creates a plain (non-pooled) DataSource pointing at the caller-specified
 * source database. This is intentionally separate from Spring Batch's own
 * JobRepository DataSource (H2), since the source DB is only known at
 * request time and varies per call.
 *
 * For production use, swap DriverManagerDataSource for a pooled HikariDataSource
 * and consider caching/reusing datasources per source.id.
 */
@Component
public class SourceDataSourceFactory {

    public DataSource build(SourceConfig source) {
        String jdbcUrl = JdbcUrlParser.extractJdbcUrl(source.getUrl());

        String username = resolve(source.getUsername(), source.getId(), "USER");
        String password = resolve(source.getPassword(), source.getId(), "PASS");

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(jdbcUrl);
        if (username != null) {
            dataSource.setUsername(username);
        }
        if (password != null) {
            dataSource.setPassword(password);
        }
        return dataSource;
    }

    /**
     * Fallback order: explicit value in JSON -> SEEDM_DB_{USER|PASS}_{sourceId} env var
     * -> SEEDM_DB_{USER|PASS} env var -> null (driver default / trust auth).
     */
    private String resolve(String explicitValue, String sourceId, String suffix) {
        if (explicitValue != null && !explicitValue.isBlank()) {
            return explicitValue;
        }
        String perSourceEnv = System.getenv("SEEDM_DB_" + suffix + "_" + sourceId);
        if (perSourceEnv != null && !perSourceEnv.isBlank()) {
            return perSourceEnv;
        }
        String globalEnv = System.getenv("SEEDM_DB_" + suffix);
        if (globalEnv != null && !globalEnv.isBlank()) {
            return globalEnv;
        }
        return null;
    }
}
