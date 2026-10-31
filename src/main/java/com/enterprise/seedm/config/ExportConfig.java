package com.enterprise.seedm.config;

import com.enterprise.seedm.util.BackupFileUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration bean for secure export multi-part file splitting.
 * Reads 'seedm.export.split-file-size' or 'seedm.export.max-file-size' configured in application.yml / application.yaml
 * and applies it to secure exports across Postgres, Mongo, and JSON services.
 */
@Configuration
public class ExportConfig {

    private static final Logger log = LoggerFactory.getLogger(ExportConfig.class);

    @Value("${seedm.export.split-file-size:${seedm.export.max-file-size:100MB}}")
    private String splitFileSize;

    @PostConstruct
    public void init() {
        if (splitFileSize != null && !splitFileSize.trim().isEmpty()) {
            long bytes = BackupFileUtils.parseDataSizeToBytes(splitFileSize.trim());
            BackupFileUtils.setMaxFileSizeBytes(bytes);
            log.info("Initialized secure export split file size from configuration: {} ({} bytes)", splitFileSize, bytes);
        }
    }

    public String getSplitFileSize() {
        return splitFileSize;
    }

    public void setSplitFileSize(String splitFileSize) {
        this.splitFileSize = splitFileSize;
        init();
    }

    public long getSplitFileSizeBytes() {
        return BackupFileUtils.parseDataSizeToBytes(splitFileSize);
    }
}
