package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.SecureExportConfig;
import com.enterprise.seedm.model.SecureExportJob;
import com.enterprise.seedm.repository.SecureExportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SecureExportService {

    @Autowired
    private SecureExportJobRepository jobRepository;

    @Autowired
    private CosConnectionService cosConnectionService;

    @Autowired
    private IbmCosService ibmCosService;

    private final ObjectMapper objectMapper;
    private final Faker faker;
    private final FormatPreservingEncryptionService fpeService;
    private final MaskingConfigService maskingConfigService;
    private static final String DEFAULT_EXPORT_DIR = "secure-export";

    @org.springframework.beans.factory.annotation.Value("${seedm.export.split-file-size:${seedm.export.max-file-size:}}")
    private String configuredSplitFileSize;

    public void setSplitFileSize(String splitFileSize) {
        this.configuredSplitFileSize = splitFileSize;
    }

    public long resolveMaxFileSizeBytes() {
        if (configuredSplitFileSize != null && !configuredSplitFileSize.trim().isEmpty()) {
            try {
                return com.enterprise.seedm.util.BackupFileUtils.parseDataSizeToBytes(configuredSplitFileSize);
            } catch (Exception e) {
                log.warn("Failed to parse configured split file size: {}", configuredSplitFileSize, e);
            }
        }
        return com.enterprise.seedm.util.BackupFileUtils.getMaxFileSizeBytes();
    }

    // In-memory store for real-time progress tracking
    private final Map<String, SecureExportProgress> progressMap = new ConcurrentHashMap<>();

    private enum DbDialect {
        POSTGRES,
        ORACLE,
        UNKNOWN
    }

    public static class ColumnInfo {
        public String name;
        public String typeName;
        public int dataType;
        public int columnSize;
        public int decimalDigits;
        public boolean isNullable;

        public ColumnInfo(String name, String typeName, int dataType, int columnSize, int decimalDigits, boolean isNullable) {
            this.name = name;
            this.typeName = typeName != null ? typeName.toLowerCase() : "varchar";
            this.dataType = dataType;
            this.columnSize = columnSize;
            this.decimalDigits = decimalDigits;
            this.isNullable = isNullable;
        }
    }

    private final DataMaskingService dataMaskingService;

    public SecureExportService(ObjectMapper objectMapper, Faker faker, FormatPreservingEncryptionService fpeService, MaskingConfigService maskingConfigService) {
        this(objectMapper, faker, fpeService, maskingConfigService, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SecureExportService(ObjectMapper objectMapper, Faker faker, FormatPreservingEncryptionService fpeService, MaskingConfigService maskingConfigService, @org.springframework.context.annotation.Lazy DataMaskingService dataMaskingService, SecureExportJobRepository jobRepository, CosConnectionService cosConnectionService, IbmCosService ibmCosService) {
        this.objectMapper = objectMapper;
        this.faker = faker;
        this.fpeService = fpeService;
        this.maskingConfigService = maskingConfigService;
        this.dataMaskingService = dataMaskingService;
        this.jobRepository = jobRepository;
        this.cosConnectionService = cosConnectionService;
        this.ibmCosService = ibmCosService;
    }

    public String processSecureExport(String executionId, SecureExportConfig config) {
        log.info("Starting secure export for execution ID: {}", executionId);

        SecureExportProgress progress = progressMap.computeIfAbsent(executionId, k -> new SecureExportProgress());
        progress.executionId = executionId;
        progress.setStatus("RUNNING");
        progress.setStartTime(System.currentTimeMillis());

        saveJob(executionId, config, "RUNNING", null);
        int processedTables = 0;
        List<String> tableNames = new ArrayList<>();

        String schema = (config.getSource() != null && config.getSource().getSchema() != null && !config.getSource().getSchema().trim().isEmpty())
                ? config.getSource().getSchema().trim()
                : "public";

        String saltKey = (config.getRules() != null && config.getRules().getMaskingKey() != null && !config.getRules().getMaskingKey().trim().isEmpty())
                ? config.getRules().getMaskingKey().trim()
                : (maskingConfigService != null && maskingConfigService.getConfig() != null ? maskingConfigService.getConfig().getMaskingKey() : null);

        String fileEncryptionKey = (config.getFileEncryptionKey() != null && !config.getFileEncryptionKey().trim().isEmpty())
                ? config.getFileEncryptionKey().trim()
                : saltKey;
        progress.setFileEncryptionKey(fileEncryptionKey);

        try (Connection connection = DriverManager.getConnection(config.getSource().getUrl(), config.getSource().getUsername(), config.getSource().getPassword())) {
            DatabaseMetaData metaData = connection.getMetaData();
            DbDialect dialect = getDbDialect(metaData);
            tableNames = getTableNames(metaData, schema);

            if (config.getRules() != null && config.getRules().getTargetTables() != null && !config.getRules().getTargetTables().isEmpty()) {
                tableNames = tableNames.stream()
                        .filter(tableName -> config.getRules().getTargetTables().contains(tableName))
                        .collect(Collectors.toList());
            }

            int totalTables = tableNames.size();
            progress.totalTables.set(totalTables);
            progress.tableProgress.clear();
            for (String tbl : tableNames) {
                Map<String, Object> entry = new ConcurrentHashMap<>();
                entry.put("tableName", tbl);
                entry.put("readCount", 0);
                entry.put("writeCount", 0);
                entry.put("status", "PENDING");
                progress.tableProgress.add(entry);
            }
            updateProgress(executionId, "RUNNING", 0, totalTables, null);

            String destDirStr = null;
            if (config.getDest() != null && config.getDest().getDestDir() != null && !config.getDest().getDestDir().isBlank()) {
                destDirStr = config.getDest().getDestDir().trim();
            } else if (config.getDest() != null && config.getDest().getPath() != null && !config.getDest().getPath().isBlank()) {
                destDirStr = config.getDest().getPath().trim();
            } else if (config.getStorage() != null && config.getStorage().getPath() != null && !config.getStorage().getPath().isBlank()) {
                destDirStr = config.getStorage().getPath().trim();
            } else if (config.getStorage() != null && (config.getStorage().getId() != null || config.getStorage().getCosId() != null) && cosConnectionService != null) {
                Long sId = config.getStorage().getId() != null ? config.getStorage().getId() : config.getStorage().getCosId();
                CosConnection sConn = cosConnectionService.getConnection(sId);
                if (sConn != null && sConn.getStorageLocation() != null && !sConn.getStorageLocation().isBlank()) {
                    destDirStr = sConn.getStorageLocation().trim();
                }
            }
            if (destDirStr == null || destDirStr.trim().isEmpty()) {
                destDirStr = DEFAULT_EXPORT_DIR;
                log.warn("Destination directory not specified, using default: {}", destDirStr);
            }

            Path destDir = Paths.get(destDirStr);
            Files.createDirectories(destDir);

            String encryptKey = (fileEncryptionKey != null && !fileEncryptionKey.trim().isEmpty()) ? fileEncryptionKey : saltKey;
            long maxFileSizeBytes = resolveMaxFileSizeBytes();
            List<Path> finalExportFiles;

            try (SqlBackupRotator writer = new SqlBackupRotator(destDir, "secure-export", encryptKey, maxFileSizeBytes)) {
                for (int i = 0; i < tableNames.size(); i++) {
                    String tableName = tableNames.get(i);
                    for (Map<String, Object> tp : progress.tableProgress) {
                        if (tableName.equals(tp.get("tableName"))) {
                            tp.put("status", "RUNNING");
                            break;
                        }
                    }
                    int rowCount = processTable(writer, connection, metaData, tableName, schema, config, saltKey, progress);
                    processedTables++;
                    progress.processedTables.set(processedTables);
                    progress.completedTables.add(tableName);
                    for (Map<String, Object> tp : progress.tableProgress) {
                        if (tableName.equals(tp.get("tableName"))) {
                            tp.put("status", "COMPLETED");
                            tp.put("readCount", rowCount);
                            tp.put("writeCount", rowCount);
                            break;
                        }
                    }
                    updateProgress(executionId, "RUNNING", processedTables, totalTables, null);
                }
                writeForeignKeyConstraints(writer, metaData, tableNames, schema);
                writeViews(writer, connection, dialect, schema);
                writeTriggers(writer, connection, dialect, schema);
                writeSequences(writer, connection, dialect, schema);

                writer.close();
                finalExportFiles = writer.getExportedFiles();
            }

            // Upload to COS bucket if destination is Cloud Object Storage
            Long cosId = null;
            if (config.getDest() != null && "cos".equalsIgnoreCase(config.getDest().getType())) {
                cosId = config.getDest().getCosId() != null ? config.getDest().getCosId() : config.getDest().getId();
            } else if (config.getStorage() != null && "cos".equalsIgnoreCase(config.getStorage().getType())) {
                cosId = config.getStorage().getId() != null ? config.getStorage().getId() : config.getStorage().getCosId();
            }

            if (cosId != null && cosConnectionService != null && ibmCosService != null) {
                try {
                    CosConnection cosConn = cosConnectionService.getConnection(cosId);
                    if (cosConn != null && !"Local".equalsIgnoreCase(cosConn.getStorageType())) {
                        for (Path exportFile : finalExportFiles) {
                            if (Files.exists(exportFile)) {
                                ibmCosService.uploadFile(cosConn, exportFile.getFileName().toString(), exportFile);
                                log.info("Successfully uploaded secure SQL export part to COS bucket {}: {}", ibmCosService.getEffectiveBucketName(cosConn), exportFile.getFileName());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to upload secure export parts to COS bucket", e);
                }
            }

            updateProgress(executionId, "COMPLETED", processedTables, totalTables, null);
            log.info("Secure export completed for execution ID: {} with {} backup file(s)", executionId, finalExportFiles.size());
        } catch (Exception e) {
            log.error("Secure export failed for execution ID: {}", executionId, e);
            updateProgress(executionId, "FAILED", processedTables, tableNames.size(), e.getMessage());
        }

        return executionId;
    }

    public static class SqlBackupRotator implements AutoCloseable {
        private final Path destDir;
        private final String baseName;
        private final String encryptKey;
        private final long maxSizeBytes;
        private final List<Path> exportedFiles = new ArrayList<>();

        private int partNumber = 1;
        private long currentFileBytes = 0;
        private Path currentTempFile;
        private BufferedWriter currentWriter;

        public SqlBackupRotator(Path destDir, String baseName, String encryptKey, long maxSizeBytes) throws IOException {
            this.destDir = destDir;
            this.baseName = baseName;
            this.encryptKey = encryptKey;
            this.maxSizeBytes = maxSizeBytes;
            openPart(1, false);
        }

        private void openPart(int partNum, boolean isRotated) throws IOException {
            this.partNumber = partNum;
            String tempFileName = isRotated ? (baseName + "-part" + partNum + ".sql") : (baseName + ".sql");
            this.currentTempFile = destDir.resolve(tempFileName);
            this.currentWriter = new BufferedWriter(Files.newBufferedWriter(currentTempFile, StandardCharsets.UTF_8), 128 * 1024);
            this.currentFileBytes = 0;
        }

        public void write(String s) throws IOException {
            currentWriter.write(s);
            currentFileBytes += s.getBytes(StandardCharsets.UTF_8).length;
        }

        public void newLine() throws IOException {
            currentWriter.newLine();
            currentFileBytes += 1;
        }

        public void checkRotate(long estimatedNextBytes) throws Exception {
            if (currentFileBytes > 0 && (currentFileBytes + estimatedNextBytes >= maxSizeBytes)) {
                rotate();
            }
        }

        private void rotate() throws Exception {
            currentWriter.flush();
            currentWriter.close();

            Path targetFile;
            if (partNumber == 1) {
                targetFile = destDir.resolve(baseName + "-part1.sql.enc");
            } else {
                targetFile = destDir.resolve(baseName + "-part" + partNumber + ".sql.enc");
            }

            if (encryptKey != null && !encryptKey.trim().isEmpty()) {
                com.enterprise.seedm.util.BackupFileUtils.encryptFileWithSalt(currentTempFile, targetFile, encryptKey);
                try {
                    Files.deleteIfExists(currentTempFile);
                } catch (Exception ex) {
                    log.warn("Could not remove temp SQL file: {}", ex.getMessage());
                }
                exportedFiles.add(targetFile);
            } else {
                Path plainTarget = destDir.resolve(baseName + "-part" + partNumber + ".sql");
                if (!currentTempFile.equals(plainTarget)) {
                    Files.move(currentTempFile, plainTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    exportedFiles.add(plainTarget);
                } else {
                    exportedFiles.add(currentTempFile);
                }
            }

            openPart(partNumber + 1, true);
        }

        @Override
        public void close() throws Exception {
            if (currentWriter != null) {
                currentWriter.flush();
                currentWriter.close();

                Path targetFile;
                if (partNumber == 1 && exportedFiles.isEmpty()) {
                    targetFile = destDir.resolve(baseName + ".sql.enc");
                } else {
                    targetFile = destDir.resolve(baseName + "-part" + partNumber + ".sql.enc");
                }

                if (encryptKey != null && !encryptKey.trim().isEmpty()) {
                    com.enterprise.seedm.util.BackupFileUtils.encryptFileWithSalt(currentTempFile, targetFile, encryptKey);
                    try {
                        Files.deleteIfExists(currentTempFile);
                    } catch (Exception ex) {
                        log.warn("Could not remove temp SQL file: {}", ex.getMessage());
                    }
                    exportedFiles.add(targetFile);
                } else {
                    Path plainTarget = (partNumber == 1 && exportedFiles.isEmpty())
                            ? destDir.resolve(baseName + ".sql")
                            : destDir.resolve(baseName + "-part" + partNumber + ".sql");
                    if (!currentTempFile.equals(plainTarget)) {
                        Files.move(currentTempFile, plainTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        exportedFiles.add(plainTarget);
                    } else {
                        exportedFiles.add(currentTempFile);
                    }
                }
                currentWriter = null;
            }
        }

        public List<Path> getExportedFiles() {
            return exportedFiles;
        }
    }

    public void encryptFileWithSalt(Path sourceFile, Path targetEncryptedFile, String saltKey) throws Exception {
        com.enterprise.seedm.util.BackupFileUtils.encryptFileWithSalt(sourceFile, targetEncryptedFile, saltKey);
    }

    private List<ColumnInfo> getTableColumns(DatabaseMetaData metaData, String tableName, String schema) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(null, schema, tableName, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int dataType = rs.getInt("DATA_TYPE");
                int columnSize = rs.getInt("COLUMN_SIZE");
                int decimalDigits = rs.getInt("DECIMAL_DIGITS");
                boolean isNullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                columns.add(new ColumnInfo(name, typeName, dataType, columnSize, decimalDigits, isNullable));
            }
        }
        if (columns.isEmpty()) {
            try (ResultSet rs = metaData.getColumns(null, null, tableName, null)) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String typeName = rs.getString("TYPE_NAME");
                    int dataType = rs.getInt("DATA_TYPE");
                    int columnSize = rs.getInt("COLUMN_SIZE");
                    int decimalDigits = rs.getInt("DECIMAL_DIGITS");
                    boolean isNullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    columns.add(new ColumnInfo(name, typeName, dataType, columnSize, decimalDigits, isNullable));
                }
            }
        }
        return columns;
    }

    private int processTable(SqlBackupRotator writer, Connection connection, DatabaseMetaData metaData, String tableName, String schema, SecureExportConfig config, String saltKey, SecureExportProgress progress) throws Exception {
        List<ColumnInfo> columns = getTableColumns(metaData, tableName, schema);

        String createSql = getCreateTableStatement(metaData, tableName, schema, columns);
        writer.checkRotate(createSql.length() + tableName.length() + 30);
        writer.write("-- CREATE TABLE " + tableName);
        writer.newLine();
        writer.write(createSql);
        writer.newLine();
        writer.newLine();

        int count = writeInsertStatements(writer, connection, tableName, schema, columns, config, saltKey, progress);
        writer.newLine();
        return count;
    }

    private int writeInsertStatements(SqlBackupRotator writer, Connection connection, String tableName, String schema, List<ColumnInfo> columns, SecureExportConfig config, String saltKey, SecureExportProgress progress) throws Exception {
        int rowCount = 0;
        String queryTable = (schema != null && !schema.isEmpty() && !"public".equalsIgnoreCase(schema)) ? (schema + "." + tableName) : tableName;

        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(2500);
            try (ResultSet resultSet = statement.executeQuery("SELECT * FROM " + queryTable)) {
                while (resultSet.next()) {
                    rowCount++;
                    if (progress != null) {
                        progress.totalRecords.incrementAndGet();
                    }
                    StringBuilder insertSql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
                    StringBuilder values = new StringBuilder("VALUES (");

                    for (int i = 0; i < columns.size(); i++) {
                        ColumnInfo col = columns.get(i);
                        Object originalValue = resultSet.getObject(col.name);
                        Object maskedValue = applyMaskingRules(tableName, col, originalValue, config, saltKey);

                        insertSql.append(col.name);
                        values.append(formatSqlValue(col, maskedValue));

                        if (i < columns.size() - 1) {
                            insertSql.append(", ");
                            values.append(", ");
                        }
                    }
                    insertSql.append(") ").append(values).append(");");
                    String stmtStr = insertSql.toString();
                    writer.checkRotate(stmtStr.length() + 2);
                    writer.write(stmtStr);
                    writer.newLine();
                }
            }
        }
        return rowCount;
    }

    private String formatSqlValue(ColumnInfo col, Object val) {
        if (val == null) {
            return "NULL";
        }
        if (val instanceof Number) {
            return val.toString();
        }
        if (val instanceof Boolean) {
            return ((Boolean) val) ? "TRUE" : "FALSE";
        }
        if (val instanceof byte[]) {
            byte[] bytes = (byte[]) val;
            StringBuilder hex = new StringBuilder("E'\\\\x");
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            hex.append("'");
            return hex.toString();
        }
        if (val instanceof Timestamp || val instanceof java.sql.Date || val instanceof java.sql.Time || val instanceof Date) {
            return "'" + val.toString() + "'";
        }
        if (val instanceof UUID) {
            return "'" + val.toString() + "'";
        }
        return "'" + val.toString().replace("'", "''") + "'";
    }

    private String getCreateTableStatement(DatabaseMetaData metaData, String tableName, String schema, List<ColumnInfo> columns) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (");
        int initialLength = sb.length();

        for (ColumnInfo col : columns) {
            sb.append(col.name).append(" ");
            String tName = col.typeName.toUpperCase();

            // Handle type formatting
            if (tName.equals("VARCHAR") || tName.equals("NVARCHAR") || tName.equals("CHAR") || tName.equals("BPCHAR")) {
                sb.append(col.typeName).append("(").append(col.columnSize > 0 ? col.columnSize : 255).append(")");
            } else if (tName.equals("NUMERIC") || tName.equals("DECIMAL")) {
                if (col.columnSize > 0 && col.decimalDigits > 0) {
                    sb.append(col.typeName).append("(").append(col.columnSize).append(",").append(col.decimalDigits).append(")");
                } else if (col.columnSize > 0) {
                    sb.append(col.typeName).append("(").append(col.columnSize).append(")");
                } else {
                    sb.append(col.typeName);
                }
            } else {
                sb.append(col.typeName);
            }

            if (!col.isNullable) {
                sb.append(" NOT NULL");
            }
            sb.append(", ");
        }

        List<String> primaryKeyColumns = getPrimaryKeyColumns(metaData, tableName, schema);
        if (!primaryKeyColumns.isEmpty()) {
            sb.append("PRIMARY KEY (").append(String.join(", ", primaryKeyColumns)).append("), ");
        }

        if (sb.length() > initialLength) {
            sb.setLength(sb.length() - 2); // Remove last comma and space
        }
        sb.append(");");
        return sb.toString();
    }

    private List<String> getPrimaryKeyColumns(DatabaseMetaData metaData, String tableName, String schema) throws SQLException {
        List<String> primaryKeyColumns = new ArrayList<>();
        try (ResultSet primaryKeys = metaData.getPrimaryKeys(null, schema, tableName)) {
            while (primaryKeys.next()) {
                primaryKeyColumns.add(primaryKeys.getString("COLUMN_NAME"));
            }
        }
        return primaryKeyColumns;
    }

    private List<String> getTableNames(DatabaseMetaData metaData, String schema) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        try (ResultSet tables = metaData.getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                tableNames.add(tables.getString("TABLE_NAME"));
            }
        }
        if (tableNames.isEmpty()) {
            try (ResultSet tables = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    tableNames.add(tables.getString("TABLE_NAME"));
                }
            }
        }
        return tableNames;
    }

    private void writeForeignKeyConstraints(SqlBackupRotator writer, DatabaseMetaData metaData, List<String> tableNames, String schema) throws IOException {
        try {
            writer.newLine();
            writer.write("-- FOREIGN KEY CONSTRAINTS");
            writer.newLine();
            for (String tableName : tableNames) {
                try (ResultSet foreignKeys = metaData.getImportedKeys(null, schema, tableName)) {
                    while (foreignKeys.next()) {
                        String fkTableName = foreignKeys.getString("FKTABLE_NAME");
                        String fkColumnName = foreignKeys.getString("FKCOLUMN_NAME");
                        String pkTableName = foreignKeys.getString("PKTABLE_NAME");
                        String pkColumnName = foreignKeys.getString("PKCOLUMN_NAME");
                        String constraintName = foreignKeys.getString("FK_NAME");

                        if (constraintName == null || constraintName.trim().isEmpty()) {
                            constraintName = "fk_" + fkTableName + "_" + fkColumnName;
                        }

                        String alterSql = "ALTER TABLE " + fkTableName + " ADD CONSTRAINT " + constraintName + " FOREIGN KEY (" + fkColumnName + ") REFERENCES " + pkTableName + "(" + pkColumnName + ");";
                        writer.checkRotate(alterSql.length() + 2);
                        writer.write(alterSql);
                        writer.newLine();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not export foreign keys: {}", e.getMessage());
        }
    }

    private void writeViews(SqlBackupRotator writer, Connection connection, DbDialect dialect, String schema) throws IOException {
        try {
            writer.newLine();
            writer.write("-- VIEWS");
            writer.newLine();
            String query;
            switch (dialect) {
                case POSTGRES:
                    query = "SELECT table_name, view_definition FROM information_schema.views WHERE table_schema = ?";
                    break;
                case ORACLE:
                    query = "SELECT view_name, text FROM user_views";
                    break;
                default:
                    return;
            }
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                if (dialect == DbDialect.POSTGRES) {
                    statement.setString(1, schema);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String viewName = resultSet.getString(1);
                        String viewDefinition = resultSet.getString(2);
                        if (viewDefinition != null && !viewDefinition.trim().isEmpty()) {
                            String viewSql = "CREATE OR REPLACE VIEW " + viewName + " AS " + viewDefinition.trim();
                            if (!viewSql.endsWith(";")) {
                                viewSql += ";";
                            }
                            writer.checkRotate(viewSql.length() + 2);
                            writer.write(viewSql);
                            writer.newLine();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not export views: {}", e.getMessage());
        }
    }

    private void writeTriggers(SqlBackupRotator writer, Connection connection, DbDialect dialect, String schema) throws IOException {
        try {
            writer.newLine();
            writer.write("-- TRIGGERS");
            writer.newLine();
            String query;
            switch (dialect) {
                case POSTGRES:
                    query = "SELECT trigger_name, action_statement FROM information_schema.triggers WHERE trigger_schema = ?";
                    break;
                case ORACLE:
                    query = "SELECT trigger_name, trigger_body FROM user_triggers";
                    break;
                default:
                    return;
            }
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                if (dialect == DbDialect.POSTGRES) {
                    statement.setString(1, schema);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String triggerName = resultSet.getString(1);
                        String actionStatement = resultSet.getString(2);
                        if (triggerName != null && actionStatement != null) {
                            writer.checkRotate(actionStatement.length() + triggerName.length() + 20);
                            writer.write("-- TRIGGER: " + triggerName);
                            writer.newLine();
                            writer.write(actionStatement);
                            writer.newLine();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not export triggers: {}", e.getMessage());
        }
    }

    private void writeSequences(SqlBackupRotator writer, Connection connection, DbDialect dialect, String schema) throws IOException {
        try {
            writer.newLine();
            writer.write("-- SEQUENCES");
            writer.newLine();
            String query;
            switch (dialect) {
                case POSTGRES:
                    query = "SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = ?";
                    break;
                case ORACLE:
                    query = "SELECT sequence_name FROM user_sequences";
                    break;
                default:
                    return;
            }
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                if (dialect == DbDialect.POSTGRES) {
                    statement.setString(1, schema);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String sequenceName = resultSet.getString(1);
                        if (sequenceName != null) {
                            String seqSql = "CREATE SEQUENCE IF NOT EXISTS " + sequenceName + ";";
                            writer.checkRotate(seqSql.length() + 2);
                            writer.write(seqSql);
                            writer.newLine();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not export sequences: {}", e.getMessage());
        }
    }

    private DbDialect getDbDialect(DatabaseMetaData metaData) throws SQLException {
        String dbName = metaData.getDatabaseProductName();
        if (dbName.equalsIgnoreCase("PostgreSQL")) {
            return DbDialect.POSTGRES;
        } else if (dbName.equalsIgnoreCase("Oracle")) {
            return DbDialect.ORACLE;
        }
        return DbDialect.UNKNOWN;
    }

    private Object applyMaskingRules(String tableName, ColumnInfo col, Object originalValue, SecureExportConfig config, String saltKey) {
        if (originalValue == null || config == null || config.getRules() == null) {
            return originalValue;
        }

        String columnName = col.name;
        String fullCol = tableName + "." + columnName;
        SecureExportConfig.RulesConfig rules = config.getRules();
        List<String> stdCols = rules.getMaskingColumns() != null ? rules.getMaskingColumns() : Collections.emptyList();
        List<String> prtCols = rules.getPartialMaskingColumns() != null ? rules.getPartialMaskingColumns() : Collections.emptyList();
        List<String> fphCols = rules.getConstraintFields() != null ? rules.getConstraintFields() : (rules.getConstraintColumns() != null ? rules.getConstraintColumns() : Collections.emptyList());
        List<String> nullCols = rules.getNullifyColumns() != null ? rules.getNullifyColumns() : Collections.emptyList();
        Map<String, String> defVals = rules.getDefaultValues() != null ? rules.getDefaultValues() : Collections.emptyMap();
        Map<String, String> customFormats = rules.getCustomFormats() != null ? rules.getCustomFormats() : Collections.emptyMap();

        if (nullCols.contains(fullCol) || nullCols.contains(columnName)) {
            return null;
        }

        if (defVals.containsKey(fullCol) || defVals.containsKey(columnName)) {
            String val = defVals.containsKey(fullCol) ? defVals.get(fullCol) : defVals.get(columnName);
            if (val == null || "null".equalsIgnoreCase(val.trim())) return null;
            return val;
        }

        if (customFormats.containsKey(fullCol) || customFormats.containsKey(columnName)) {
            String formatRule = customFormats.containsKey(fullCol) ? customFormats.get(fullCol) : customFormats.get(columnName);
            if (dataMaskingService != null && formatRule != null && !formatRule.trim().isEmpty()) {
                return dataMaskingService.applyCustomFormat(originalValue, formatRule);
            }
        }

        if (stdCols.contains(fullCol) || stdCols.contains(columnName)) {
            return generateTypeSafeFakeData(col, originalValue);
        } else if (prtCols.contains(fullCol) || prtCols.contains(columnName)) {
            return applyTypeSafePartialMasking(col, originalValue);
        } else if (fphCols.contains(fullCol) || fphCols.contains(columnName)) {
            return applyFormatPreservingEncryption(col, originalValue, saltKey);
        }
        return originalValue;
    }

    private Object applyFormatPreservingEncryption(ColumnInfo col, Object originalValue, String saltKey) {
        try {
            String type = col.typeName.toLowerCase();
            Object typedValue = originalValue;

            if (type.contains("int") || type.contains("serial")) {
                if (type.equals("smallint") || type.equals("int2")) {
                    typedValue = ((Number) originalValue).intValue();
                    type = "smallint";
                } else if (type.equals("bigint") || type.equals("int8")) {
                    typedValue = ((Number) originalValue).longValue();
                    type = "bigint";
                } else {
                    typedValue = ((Number) originalValue).intValue();
                    type = "integer";
                }
            } else if (type.contains("float") || type.contains("real")) {
                typedValue = ((Number) originalValue).floatValue();
                type = "float";
            } else if (type.contains("double") || type.contains("numeric") || type.contains("decimal")) {
                typedValue = ((Number) originalValue).doubleValue();
                type = "double";
            } else if (type.contains("bool")) {
                typedValue = originalValue instanceof Boolean ? originalValue : Boolean.valueOf(originalValue.toString());
                type = "boolean";
            } else if (type.contains("uuid")) {
                typedValue = originalValue.toString();
                type = "uuid";
            } else {
                typedValue = originalValue.toString();
                type = "string";
            }

            Object encrypted = fpeService.encrypt(typedValue, type, saltKey);
            if (encrypted != null) {
                return encrypted;
            }
        } catch (Exception e) {
            log.error("Failed to encrypt database column {}: {}", col.name, e.getMessage());
        }
        return originalValue;
    }

    private Object generateTypeSafeFakeData(ColumnInfo col, Object originalValue) {
        String lowerCol = col.name.toLowerCase();
        String type = col.typeName.toLowerCase();

        // 1. Numeric columns
        if (originalValue instanceof Number || type.contains("int") || type.contains("serial") || type.contains("numeric") || type.contains("decimal") || type.contains("float") || type.contains("double")) {
            if (type.equals("smallint") || type.equals("int2")) {
                return faker.number().numberBetween(1, 30000);
            }
            if (type.equals("bigint") || type.equals("int8")) {
                return faker.number().numberBetween(1L, 1000000L);
            }
            if (type.contains("float") || type.contains("real") || type.contains("double") || type.contains("numeric") || type.contains("decimal")) {
                return BigDecimal.valueOf(faker.number().randomDouble(2, 10, 10000));
            }
            return faker.number().numberBetween(1, 100000);
        }

        // 2. Date / Timestamp columns
        if (originalValue instanceof Date || originalValue instanceof Timestamp || type.contains("date") || type.contains("time")) {
            long randomPast = System.currentTimeMillis() - (long) (Math.random() * 365L * 24 * 3600 * 1000);
            if (originalValue instanceof java.sql.Date || type.equals("date")) {
                return new java.sql.Date(randomPast);
            }
            return new Timestamp(randomPast);
        }

        // 3. Boolean columns
        if (originalValue instanceof Boolean || type.contains("bool")) {
            return faker.bool().bool();
        }

        // 4. UUID
        if (originalValue instanceof UUID || type.contains("uuid")) {
            return UUID.randomUUID();
        }

        // 5. String / Varchar columns based on semantic name
        String result;
        if (lowerCol.contains("email")) {
            result = faker.internet().emailAddress();
        } else if (lowerCol.contains("first_name") || lowerCol.contains("firstname")) {
            result = faker.name().firstName();
        } else if (lowerCol.contains("last_name") || lowerCol.contains("lastname")) {
            result = faker.name().lastName();
        } else if (lowerCol.contains("name")) {
            result = faker.name().fullName();
        } else if (lowerCol.contains("phone")) {
            result = faker.phoneNumber().cellPhone();
        } else if (lowerCol.contains("address")) {
            result = faker.address().streetAddress();
        } else if (lowerCol.contains("city")) {
            result = faker.address().city();
        } else if (lowerCol.contains("country")) {
            result = faker.address().country();
        } else if (lowerCol.contains("zip") || lowerCol.contains("postal")) {
            result = faker.address().zipCode();
        } else if (lowerCol.contains("district")) {
            result = faker.address().state();
        } else {
            result = faker.lorem().word();
        }

        // Clamp string length to column size
        if (col.columnSize > 0 && result.length() > col.columnSize) {
            result = result.substring(0, col.columnSize);
        }
        return result;
    }

    private Object applyTypeSafePartialMasking(ColumnInfo col, Object originalValue) {
        if (originalValue == null) return null;
        String str = originalValue.toString();
        if (str.length() <= 4) {
            return str.replaceAll(".", "*");
        }
        int maskCount = str.length() - 4;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maskCount; i++) {
            sb.append("*");
        }
        sb.append(str.substring(maskCount));
        String result = sb.toString();

        if (col.columnSize > 0 && result.length() > col.columnSize) {
            result = result.substring(0, col.columnSize);
        }
        return result;
    }

    public Map<String, Object> getProgress(String executionId) {
        SecureExportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            return progress.toMap();
        }

        if (jobRepository == null) {
            return Map.of("status", "NOT_FOUND");
        }
        SecureExportJob job = jobRepository.findByExecutionId(executionId);
        if (job == null) {
            return Map.of("status", "NOT_FOUND");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("executionId", job.getExecutionId());
        map.put("jobName", job.getJobName());
        map.put("status", job.getStatus());
        map.put("errorMessage", job.getErrorMessage());
        map.put("fileEncryptionKey", job.getFileEncryptionKey());
        map.put("startTime", job.getCreatedAt());
        map.put("endTime", job.getCompletedAt());
        map.put("totalTables", job.getTotalTables() != null ? job.getTotalTables() : 0);
        map.put("processedTables", job.getProcessedTables() != null ? job.getProcessedTables() : 0);
        map.put("totalRecords", job.getTotalRecords() != null ? job.getTotalRecords() : 0L);

        List<Map<String, Object>> tableProgress = new ArrayList<>();
        List<String> completedTables = new ArrayList<>();
        if (job.getProgressDetails() != null && !job.getProgressDetails().isBlank()) {
            try {
                tableProgress = objectMapper.readValue(job.getProgressDetails(), List.class);
                for (Map<String, Object> tp : tableProgress) {
                    if ("COMPLETED".equals(tp.get("status")) && tp.get("tableName") != null) {
                        completedTables.add(tp.get("tableName").toString());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse progress details for job: {}", executionId, e);
            }
        }
        map.put("tableProgress", tableProgress);
        map.put("completedTables", completedTables);
        return map;
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> executions = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // Add from memory
        for (Map.Entry<String, SecureExportProgress> entry : progressMap.entrySet()) {
            Map<String, Object> execMap = new HashMap<>();
            execMap.put("id", entry.getKey());
            execMap.put("status", entry.getValue().getStatus());
            execMap.put("startTime", entry.getValue().getStartTime());
            executions.add(execMap);
            seenIds.add(entry.getKey());
        }

        // Add from DB
        if (jobRepository != null) {
            for (SecureExportJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
                if (!seenIds.contains(job.getExecutionId())) {
                    Map<String, Object> execMap = new HashMap<>();
                    execMap.put("id", job.getExecutionId());
                    execMap.put("status", job.getStatus());
                    execMap.put("startTime", job.getCreatedAt());
                    executions.add(execMap);
                    seenIds.add(job.getExecutionId());
                }
            }
        }
        return executions;
    }

    private void saveJob(String executionId, SecureExportConfig config, String status, String errorMessage) {
        if (jobRepository == null) return;
        try {
            SecureExportProgress progress = progressMap.get(executionId);
            SecureExportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new SecureExportJob();
                job.setExecutionId(executionId);
                job.setJobName((config != null && config.getJobName() != null && !config.getJobName().trim().isEmpty()) ? config.getJobName().trim() : executionId);
                job.setCreatedAt(System.currentTimeMillis());
            }
            job.setStatus(status);
            job.setErrorMessage(errorMessage);
            if (config != null && config.getFileEncryptionKey() != null) {
                job.setFileEncryptionKey(config.getFileEncryptionKey());
            }
            if (progress != null) {
                if (job.getFileEncryptionKey() == null && progress.getFileEncryptionKey() != null) {
                    job.setFileEncryptionKey(progress.getFileEncryptionKey());
                }
                job.setTotalTables(progress.totalTables.get());
                job.setProcessedTables(progress.processedTables.get());
                job.setTotalRecords((long) progress.totalRecords.get());
                if (progress.tableProgress != null) {
                    job.setProgressDetails(objectMapper.writeValueAsString(progress.tableProgress));
                }
            }
            if (config != null) {
                try {
                    job.setConfigDetails(objectMapper.writeValueAsString(config));
                } catch (IOException e) {
                    log.error("Failed to serialize config details", e);
                }
            }
            jobRepository.saveAndFlush(job);
        } catch (Exception e) {
            log.error("Failed to save SecureExportJob record", e);
        }
    }

    private void updateProgress(String executionId, String status, int processed, int total, String errorMessage) {
        SecureExportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            progress.setStatus(status);
            progress.processedTables.set(processed);
            progress.totalTables.set(total);
            progress.setErrorMessage(errorMessage);
            if ("FAILED".equals(status) || "COMPLETED".equals(status)) {
                progress.setEndTime(System.currentTimeMillis());
            }
        }

        if (jobRepository == null) return;
        try {
            SecureExportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new SecureExportJob();
                job.setExecutionId(executionId);
                job.setJobName(executionId);
                job.setCreatedAt(System.currentTimeMillis());
            }
            job.setStatus(status);
            job.setTotalTables(total);
            job.setProcessedTables(processed);
            if (progress != null) {
                job.setTotalRecords((long) progress.totalRecords.get());
                if (progress.getFileEncryptionKey() != null) {
                    job.setFileEncryptionKey(progress.getFileEncryptionKey());
                }
                if (progress.tableProgress != null) {
                    job.setProgressDetails(objectMapper.writeValueAsString(progress.tableProgress));
                }
            }
            job.setErrorMessage(errorMessage);
            if ("FAILED".equals(status) || "COMPLETED".equals(status)) {
                job.setCompletedAt(System.currentTimeMillis());
            }
            jobRepository.saveAndFlush(job);
        } catch (Exception e) {
            log.error("Failed to update SecureExportJob progress in DB", e);
        }
    }

    // Inner class for progress tracking
    private static class SecureExportProgress {
        private String executionId;
        private String status = "PENDING";
        private String fileEncryptionKey;
        private AtomicInteger totalTables = new AtomicInteger(0);
        private AtomicInteger processedTables = new AtomicInteger(0);
        private AtomicInteger totalRecords = new AtomicInteger(0);
        private List<String> completedTables = Collections.synchronizedList(new ArrayList<>());
        private List<Map<String, Object>> tableProgress = Collections.synchronizedList(new ArrayList<>());
        private String errorMessage;
        private Long startTime;
        private Long endTime;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getFileEncryptionKey() { return fileEncryptionKey; }
        public void setFileEncryptionKey(String fileEncryptionKey) { this.fileEncryptionKey = fileEncryptionKey; }
        public Long getStartTime() { return startTime; }
        public void setStartTime(Long startTime) { this.startTime = startTime; }
        public Long getEndTime() { return endTime; }
        public void setEndTime(Long endTime) { this.endTime = endTime; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("executionId", executionId);
            map.put("status", status);
            map.put("fileEncryptionKey", fileEncryptionKey);
            map.put("totalTables", totalTables.get());
            map.put("processedTables", processedTables.get());
            map.put("totalRecords", totalRecords.get());
            map.put("completedTables", new ArrayList<>(completedTables));
            map.put("tableProgress", new ArrayList<>(tableProgress));
            map.put("errorMessage", errorMessage);
            map.put("startTime", startTime);
            map.put("endTime", endTime);
            return map;
        }
    }
}