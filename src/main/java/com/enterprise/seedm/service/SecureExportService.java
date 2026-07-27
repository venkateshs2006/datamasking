package com.enterprise.seedm.service;

import com.enterprise.seedm.model.SecureExportConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class SecureExportService {

    private final ObjectMapper objectMapper;
    private final Faker faker;
    private final DataSource dataSource;
    private final FormatPreservingEncryptionService fpeService;

    private enum DbDialect {
        POSTGRES,
        ORACLE,
        UNKNOWN
    }

    // In-memory store for async job progress
    private final Map<String, SecureExportProgress> progressMap = new ConcurrentHashMap<>();

    public SecureExportService(ObjectMapper objectMapper, Faker faker, DataSource dataSource, FormatPreservingEncryptionService fpeService) {
        this.objectMapper = objectMapper;
        this.faker = faker;
        this.dataSource = dataSource;
        this.fpeService = fpeService;
    }

    public String processSecureExport(String executionId, SecureExportConfig config) {
        log.info("Starting secure export for execution ID: {}", executionId);
        updateProgress(executionId, "RUNNING", 0, 0, null);

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            DbDialect dialect = getDbDialect(metaData);
            List<String> tableNames = getTableNames(metaData);
            int totalTables = tableNames.size();
            updateProgress(executionId, "RUNNING", 0, totalTables, null);

            Path destDir = Paths.get(config.getDest().getDestDir());
            Files.createDirectories(destDir);
            Path filePath = destDir.resolve("secure-export.sql");

            try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                for (int i = 0; i < tableNames.size(); i++) {
                    String tableName = tableNames.get(i);
                    processTable(writer, connection, metaData, tableName, config);
                    updateProgress(executionId, "RUNNING", i + 1, totalTables, null);
                }
                writeForeignKeyConstraints(writer, metaData, tableNames);
                writeViews(writer, connection, dialect);
                writeTriggers(writer, connection, dialect);
                writeSequences(writer, connection, dialect);
            }

            updateProgress(executionId, "COMPLETED", totalTables, totalTables, null);
            log.info("Secure export completed for execution ID: {}", executionId);
        } catch (SQLException | IOException e) {
            log.error("Secure export failed for execution ID: {}", executionId, e);
            updateProgress(executionId, "FAILED", 0, 0, e.getMessage());
        }

        return executionId;
    }

    private void processTable(BufferedWriter writer, Connection connection, DatabaseMetaData metaData, String tableName, SecureExportConfig config) throws SQLException, IOException {
        writer.write("-- CREATE TABLE " + tableName);
        writer.newLine();
        writer.write(getCreateTableStatement(metaData, tableName));
        writer.newLine();
        writer.newLine();

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM " + tableName)) {
            writeInsertStatements(writer, resultSet, tableName, config);
        }
        writer.newLine();
    }

    private void writeInsertStatements(BufferedWriter writer, ResultSet resultSet, String tableName, SecureExportConfig config) throws SQLException, IOException {
        ResultSetMetaData rsMetaData = resultSet.getMetaData();
        int columnCount = rsMetaData.getColumnCount();

        while (resultSet.next()) {
            writer.write("INSERT INTO " + tableName + " VALUES (");
            for (int i = 1; i <= columnCount; i++) {
                String columnName = rsMetaData.getColumnName(i);
                String value = resultSet.getString(i);
                String maskedValue = applyMaskingRules(columnName, value, config);
                writer.write("'" + maskedValue + "'");
                if (i < columnCount) {
                    writer.write(", ");
                }
            }
            writer.write(");");
            writer.newLine();
        }
    }

    private String getCreateTableStatement(DatabaseMetaData metaData, String tableName) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (");
        int initialLength = sb.length();
        try (ResultSet columns = metaData.getColumns(null, null, tableName, null)) {
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String columnType = columns.getString("TYPE_NAME");
                int columnSize = columns.getInt("COLUMN_SIZE");
                String isNullable = columns.getString("IS_NULLABLE");
                sb.append(columnName).append(" ").append(columnType).append("(").append(columnSize).append(")");
                if ("NO".equalsIgnoreCase(isNullable)) {
                    sb.append(" NOT NULL");
                }
                sb.append(", ");
            }
        }

        List<String> primaryKeyColumns = getPrimaryKeyColumns(metaData, tableName);
        if (!primaryKeyColumns.isEmpty()) {
            sb.append("PRIMARY KEY (").append(String.join(", ", primaryKeyColumns)).append("), ");
        }

        if (sb.length() > initialLength) {
            sb.setLength(sb.length() - 2); // Remove last comma and space
        }
        sb.append(");");
        return sb.toString();
    }

    private List<String> getPrimaryKeyColumns(DatabaseMetaData metaData, String tableName) throws SQLException {
        List<String> primaryKeyColumns = new ArrayList<>();
        try (ResultSet primaryKeys = metaData.getPrimaryKeys(null, null, tableName)) {
            while (primaryKeys.next()) {
                primaryKeyColumns.add(primaryKeys.getString("COLUMN_NAME"));
            }
        }
        return primaryKeyColumns;
    }

    private List<String> getTableNames(DatabaseMetaData metaData) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        try (ResultSet tables = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                tableNames.add(tables.getString("TABLE_NAME"));
            }
        }
        return tableNames;
    }

    private void writeForeignKeyConstraints(BufferedWriter writer, DatabaseMetaData metaData, List<String> tableNames) throws SQLException, IOException {
        writer.newLine();
        writer.write("-- FOREIGN KEY CONSTRAINTS");
        writer.newLine();
        for (String tableName : tableNames) {
            try (ResultSet foreignKeys = metaData.getImportedKeys(null, null, tableName)) {
                while (foreignKeys.next()) {
                    String fkTableName = foreignKeys.getString("FKTABLE_NAME");
                    String fkColumnName = foreignKeys.getString("FKCOLUMN_NAME");
                    String pkTableName = foreignKeys.getString("PKTABLE_NAME");
                    String pkColumnName = foreignKeys.getString("PKCOLUMN_NAME");
                    String constraintName = foreignKeys.getString("FK_NAME");

                    writer.write("ALTER TABLE " + fkTableName + " ADD CONSTRAINT " + constraintName + " FOREIGN KEY (" + fkColumnName + ") REFERENCES " + pkTableName + "(" + pkColumnName + ");");
                    writer.newLine();
                }
            }
        }
    }

    private void writeViews(BufferedWriter writer, Connection connection, DbDialect dialect) throws SQLException, IOException {
        writer.newLine();
        writer.write("-- VIEWS");
        writer.newLine();
        String query;
        switch (dialect) {
            case POSTGRES:
                query = "SELECT view_name, view_definition FROM information_schema.views WHERE table_schema = 'public'";
                break;
            case ORACLE:
                query = "SELECT view_name, text FROM user_views";
                break;
            default:
                return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            while (resultSet.next()) {
                String viewName = resultSet.getString(1);
                String viewDefinition = resultSet.getString(2);
                writer.write("CREATE OR REPLACE VIEW " + viewName + " AS " + viewDefinition);
                writer.newLine();
            }
        }
    }

    private void writeTriggers(BufferedWriter writer, Connection connection, DbDialect dialect) throws SQLException, IOException {
        writer.newLine();
        writer.write("-- TRIGGERS");
        writer.newLine();
        String query;
        switch (dialect) {
            case POSTGRES:
                query = "SELECT trigger_name, action_statement FROM information_schema.triggers WHERE trigger_schema = 'public'";
                break;
            case ORACLE:
                query = "SELECT trigger_name, trigger_body FROM user_triggers";
                break;
            default:
                return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            while (resultSet.next()) {
                String triggerName = resultSet.getString(1);
                String actionStatement = resultSet.getString(2);
                writer.write("-- TRIGGER: " + triggerName);
                writer.newLine();
                writer.write(actionStatement);
                writer.newLine();
            }
        }
    }

    private void writeSequences(BufferedWriter writer, Connection connection, DbDialect dialect) throws SQLException, IOException {
        writer.newLine();
        writer.write("-- SEQUENCES");
        writer.newLine();
        String query;
        switch (dialect) {
            case POSTGRES:
                query = "SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = 'public'";
                break;
            case ORACLE:
                query = "SELECT sequence_name FROM user_sequences";
                break;
            default:
                return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            while (resultSet.next()) {
                String sequenceName = resultSet.getString(1);
                writer.write("CREATE SEQUENCE " + sequenceName + ";");
                writer.newLine();
            }
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

    private String applyMaskingRules(String columnName, String value, SecureExportConfig config) {
        if (value == null) {
            return null;
        }

        SecureExportConfig.RulesConfig rules = config.getRules();
        if (rules.getMaskingColumns() != null && rules.getMaskingColumns().contains(columnName)) {
            return generateFakeData(columnName);
        } else if (rules.getPartialMaskingColumns() != null && rules.getPartialMaskingColumns().contains(columnName)) {
            return applyPartialMasking(value);
        } else if (rules.getConstraintFields() != null && rules.getConstraintFields().contains(columnName)) {
            try {
                return (String) fpeService.encrypt(value, "string");
            } catch (Exception e) {
                log.error("Failed to encrypt database column {}", columnName, e);
                return value;
            }
        }
        return value;
    }

    private String generateFakeData(String fieldPath) {
        String lower = fieldPath.toLowerCase();
        if (lower.contains("name")) {
            return faker.name().fullName();
        } else if (lower.contains("email")) {
            return faker.internet().emailAddress();
        } else if (lower.contains("phone")) {
            return faker.phoneNumber().phoneNumber();
        } else if (lower.contains("city")) {
            return faker.address().city();
        } else if (lower.contains("address")) {
            return faker.address().fullAddress();
        }
        return faker.lorem().word();
    }

    private String applyPartialMasking(String value) {
        if (value.length() <= 4) {
            return value.replaceAll(".", "*");
        }
        int maskCount = value.length() - 4;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maskCount; i++) {
            sb.append("*");
        }
        sb.append(value.substring(maskCount));
        return sb.toString();
    }

    public Map<String, Object> getProgress(String executionId) {
        SecureExportProgress progress = progressMap.get(executionId);
        if (progress == null) {
            return Map.of("status", "NOT_FOUND");
        }
        return progress.toMap();
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> executions = new ArrayList<>();
        for (Map.Entry<String, SecureExportProgress> entry : progressMap.entrySet()) {
            Map<String, Object> execMap = new HashMap<>();
            execMap.put("id", entry.getKey());
            execMap.put("status", entry.getValue().getStatus());
            execMap.put("startTime", entry.getValue().getStartTime());
            executions.add(execMap);
        }
        return executions;
    }

    private void updateProgress(String executionId, String status, int processed, int total, String errorMessage) {
        SecureExportProgress progress = progressMap.computeIfAbsent(executionId, k -> new SecureExportProgress());
        progress.setStatus(status);
        progress.setProcessedItems(processed);
        progress.setTotalItems(total);
        progress.setErrorMessage(errorMessage);
        if ("FAILED".equals(status) || "COMPLETED".equals(status)) {
            progress.setEndTime(System.currentTimeMillis());
        }
    }

    // Inner class for progress tracking
    private static class SecureExportProgress {
        private String status = "PENDING";
        private AtomicInteger totalItems = new AtomicInteger(0);
        private AtomicInteger processedItems = new AtomicInteger(0);
        private String errorMessage;
        private Long startTime;
        private Long endTime;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getTotalItems() { return totalItems.get(); }
        public void setTotalItems(int totalItems) { this.totalItems.set(totalItems); }
        public int getProcessedItems() { return processedItems.get(); }
        public void setProcessedItems(int processedItems) { this.processedItems.set(processedItems); }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public Long getStartTime() { return startTime; }
        public void setStartTime(Long startTime) { this.startTime = startTime; }
        public Long getEndTime() { return endTime; }
        public void setEndTime(Long endTime) { this.endTime = endTime; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("status", status);
            map.put("totalItems", totalItems.get());
            map.put("processedItems", processedItems.get());
            map.put("errorMessage", errorMessage);
            map.put("startTime", startTime);
            map.put("endTime", endTime);
            return map;
        }
    }
}