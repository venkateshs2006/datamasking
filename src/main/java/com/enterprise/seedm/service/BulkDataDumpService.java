package com.enterprise.seedm.service;

import com.enterprise.seedm.model.BulkDumpConfig;
import com.enterprise.seedm.model.BulkDumpProgress;
import com.enterprise.seedm.model.BulkDumpTableConfig;
import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.DbConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javafaker.Faker;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class BulkDataDumpService {

    private final DbConnectionService dbConnectionService;
    private final CosConnectionService cosConnectionService;
    private final MongoConnectionHelper mongoConnectionHelper;
    private final ObjectMapper objectMapper;
    private final IbmCosService ibmCosService;

    private final Faker faker = new Faker();
    private final Random random = new Random();

    private final Map<String, BulkDumpProgress> progressMap = new ConcurrentHashMap<>();
    private final AtomicLong executionSequence = new AtomicLong(System.currentTimeMillis() % 100000);

    public String generateExecutionId() {
        return "bulk-dump-" + executionSequence.incrementAndGet();
    }

    public BulkDumpProgress getProgress(String executionId) {
        if (executionId == null) return null;
        BulkDumpProgress direct = progressMap.get(executionId);
        if (direct != null) return direct;
        try {
            Long jId = Long.parseLong(executionId);
            for (BulkDumpProgress p : progressMap.values()) {
                if (jId.equals(p.getJobId())) return p;
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    /**
     * Step 2 & 3: Discover tables/collections/entities along with their current record count & constraints
     */
    public List<BulkDumpTableConfig> discoverTablesWithCounts(String dbType, Long connectionId, String schemaOrDb, String dirPath) {
        String normalizedType = dbType != null ? dbType.toLowerCase().trim() : "sql";

        if (normalizedType.contains("mongo")) {
            return discoverMongoCollectionsWithCounts(connectionId, schemaOrDb);
        } else if (normalizedType.contains("json")) {
            return discoverJsonTablesWithCounts(connectionId, dirPath);
        } else if (normalizedType.contains("csv")) {
            return discoverCsvTablesWithCounts(connectionId, dirPath);
        } else {
            return discoverSqlTablesWithCounts(connectionId, schemaOrDb);
        }
    }

    private List<BulkDumpTableConfig> discoverSqlTablesWithCounts(Long connectionId, String schemaName) {
        List<BulkDumpTableConfig> result = new ArrayList<>();
        DbConnection conn = dbConnectionService.getConnection(connectionId);
        if (conn == null) {
            throw new IllegalArgumentException("Database connection not found: " + connectionId);
        }

        String targetSchema = (schemaName != null && !schemaName.trim().isEmpty()) ? schemaName.trim() : "public";

        try (Connection jdbcConn = DriverManager.getConnection(conn.getUrl(), conn.getUsername(), conn.getPassword())) {
            DatabaseMetaData metaData = jdbcConn.getMetaData();

            List<String> tableNames = new ArrayList<>();
            try (ResultSet rs = metaData.getTables(null, targetSchema, "%", new String[]{"TABLE", "BASE TABLE"})) {
                while (rs.next()) {
                    String tbl = rs.getString("TABLE_NAME");
                    if (tbl != null && !tbl.toLowerCase().startsWith("batch_")) {
                        tableNames.add(tbl);
                    }
                }
            }

            if (tableNames.isEmpty()) {
                try (ResultSet rs = metaData.getTables(null, targetSchema.toUpperCase(), "%", new String[]{"TABLE", "BASE TABLE"})) {
                    while (rs.next()) {
                        String tbl = rs.getString("TABLE_NAME");
                        if (tbl != null && !tbl.toLowerCase().startsWith("batch_")) {
                            tableNames.add(tbl);
                        }
                    }
                }
            }

            for (String tableName : tableNames) {
                List<String> columns = new ArrayList<>();
                try (ResultSet colRs = metaData.getColumns(null, targetSchema, tableName, "%")) {
                    while (colRs.next()) {
                        columns.add(colRs.getString("COLUMN_NAME"));
                    }
                }

                // Primary keys
                List<String> primaryKeys = new ArrayList<>();
                try (ResultSet pkRs = metaData.getPrimaryKeys(null, targetSchema, tableName)) {
                    while (pkRs.next()) {
                        String pk = pkRs.getString("COLUMN_NAME");
                        if (pk != null && !primaryKeys.contains(pk)) {
                            primaryKeys.add(pk);
                        }
                    }
                } catch (Exception ignored) {}

                // Foreign keys
                List<Map<String, String>> foreignKeys = new ArrayList<>();
                try (ResultSet fkRs = metaData.getImportedKeys(null, targetSchema, tableName)) {
                    while (fkRs.next()) {
                        Map<String, String> fk = new HashMap<>();
                        fk.put("column", fkRs.getString("FKCOLUMN_NAME"));
                        fk.put("foreignTable", fkRs.getString("PKTABLE_NAME"));
                        fk.put("foreignColumn", fkRs.getString("PKCOLUMN_NAME"));
                        fk.put("name", fkRs.getString("FK_NAME"));
                        foreignKeys.add(fk);
                    }
                } catch (Exception ignored) {}

                // Unique keys
                List<String> uniqueKeys = new ArrayList<>();
                try (ResultSet idxRs = metaData.getIndexInfo(null, targetSchema, tableName, true, false)) {
                    while (idxRs.next()) {
                        boolean nonUnique = idxRs.getBoolean("NON_UNIQUE");
                        String col = idxRs.getString("COLUMN_NAME");
                        if (!nonUnique && col != null && !primaryKeys.contains(col) && !uniqueKeys.contains(col)) {
                            uniqueKeys.add(col);
                        }
                    }
                } catch (Exception ignored) {}

                long count = 0;
                String countSql = String.format("SELECT COUNT(*) FROM \"%s\".\"%s\"", targetSchema, tableName);
                try (Statement stmt = jdbcConn.createStatement();
                     ResultSet countRs = stmt.executeQuery(countSql)) {
                    if (countRs.next()) {
                        count = countRs.getLong(1);
                    }
                } catch (Exception e) {
                    log.warn("Could not get count for table {}.{}: {}", targetSchema, tableName, e.getMessage());
                }

                result.add(BulkDumpTableConfig.builder()
                        .tableName(tableName)
                        .currentCount(count)
                        .targetCount(100) // Default recommended bulk dump count
                        .columnCount(columns.size())
                        .columns(columns)
                        .primaryKeys(primaryKeys)
                        .foreignKeys(foreignKeys)
                        .uniqueKeys(uniqueKeys)
                        .build());
            }

        } catch (Exception e) {
            log.error("Failed to discover SQL tables for connection {}", connectionId, e);
            throw new RuntimeException("Failed to discover SQL tables: " + e.getMessage(), e);
        }

        return result;
    }

    private List<BulkDumpTableConfig> discoverMongoCollectionsWithCounts(Long connectionId, String databaseName) {
        List<BulkDumpTableConfig> result = new ArrayList<>();
        if (databaseName == null || databaseName.trim().isEmpty()) {
            databaseName = "test";
        }

        try (MongoClient client = mongoConnectionHelper.createClient(connectionId)) {
            MongoDatabase db = client.getDatabase(databaseName);
            for (String collName : db.listCollectionNames()) {
                if (collName.startsWith("system.")) continue;

                MongoCollection<Document> coll = db.getCollection(collName);
                long count = 0;
                try {
                    count = coll.estimatedDocumentCount();
                } catch (Exception countEx) {
                    try {
                        count = coll.countDocuments();
                    } catch (Exception ignored) {}
                }

                Set<String> fields = new HashSet<>();
                try {
                    for (Document doc : coll.find().limit(5)) {
                        fields.addAll(doc.keySet());
                    }
                } catch (Exception ignored) {}

                result.add(BulkDumpTableConfig.builder()
                        .tableName(collName)
                        .currentCount(count)
                        .targetCount(100)
                        .columnCount(fields.size())
                        .columns(new ArrayList<>(fields))
                        .primaryKeys(List.of("_id"))
                        .foreignKeys(new ArrayList<>())
                        .uniqueKeys(new ArrayList<>())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Could not inspect collections for MongoDB connection {} on db {}: {}", connectionId, databaseName, e.getMessage());
        }

        return result;
    }

    private List<BulkDumpTableConfig> discoverJsonTablesWithCounts(Long connectionId, String dirPath) {
        List<BulkDumpTableConfig> result = new ArrayList<>();
        String resolvedPath = dirPath;

        if (connectionId != null) {
            CosConnection cos = cosConnectionService.getConnection(connectionId);
            if (cos != null && "COS".equalsIgnoreCase(cos.getStorageType())) {
                try {
                    List<Map<String, Object>> objects = ibmCosService.listObjects(cos, dirPath);
                    List<String> jsonFiles = objects.stream()
                            .map(o -> (String) o.get("name"))
                            .filter(n -> n != null && n.toLowerCase().endsWith(".json"))
                            .toList();

                    for (String key : jsonFiles) {
                        String name = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                        Set<String> keys = ibmCosService.extractJsonKeys(cos, key);
                        List<String> keyList = new ArrayList<>(keys);
                        result.add(BulkDumpTableConfig.builder()
                                .tableName(name)
                                .currentCount(1)
                                .targetCount(100)
                                .columnCount(keyList.size())
                                .columns(keyList)
                                .primaryKeys(keyList.contains("id") ? List.of("id") : List.of())
                                .foreignKeys(new ArrayList<>())
                                .uniqueKeys(new ArrayList<>())
                                .build());
                    }
                    if (!result.isEmpty()) {
                        return result;
                    }
                } catch (Exception e) {
                    log.warn("Could not list COS objects for connection {}: {}", connectionId, e.getMessage());
                }
                return List.of(
                        BulkDumpTableConfig.builder().tableName("customers.json").currentCount(0).targetCount(100).columnCount(6)
                                .columns(List.of("id", "fullName", "email", "phone", "city", "status")).primaryKeys(List.of("id")).build(),
                        BulkDumpTableConfig.builder().tableName("transactions.json").currentCount(0).targetCount(100).columnCount(7)
                                .columns(List.of("id", "customerId", "amount", "currency", "status", "createdDate", "card")).primaryKeys(List.of("id")).build(),
                        BulkDumpTableConfig.builder().tableName("accounts.json").currentCount(0).targetCount(100).columnCount(5)
                                .columns(List.of("id", "accountNumber", "balance", "department", "status")).primaryKeys(List.of("id")).build()
                );
            } else if (cos != null && "Local".equalsIgnoreCase(cos.getStorageType())) {
                resolvedPath = cos.getStorageLocation();
            } else {
                DbConnection dbConn = dbConnectionService.getConnection(connectionId);
                if (dbConn != null && "json".equalsIgnoreCase(dbConn.getDbType())) {
                    resolvedPath = dbConn.getUrl();
                }
            }
        }

        if (resolvedPath == null || resolvedPath.trim().isEmpty()) {
            resolvedPath = "./data";
        }

        try {
            Path path = Paths.get(resolvedPath);
            if (Files.exists(path) && Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path, 1)) {
                    List<Path> jsonFiles = walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                            .toList();

                    for (Path jf : jsonFiles) {
                        String name = jf.getFileName().toString();
                        long count = 0;
                        List<String> keys = new ArrayList<>();

                        try {
                            JsonNode node = objectMapper.readTree(jf.toFile());
                            if (node.isArray()) {
                                count = node.size();
                                if (count > 0 && node.get(0).isObject()) {
                                    node.get(0).fieldNames().forEachRemaining(keys::add);
                                }
                            } else if (node.isObject()) {
                                count = 1;
                                node.fieldNames().forEachRemaining(keys::add);
                            }
                        } catch (Exception ex) {
                            log.warn("Could not read JSON file {}: {}", name, ex.getMessage());
                        }

                        result.add(BulkDumpTableConfig.builder()
                                .tableName(name)
                                .currentCount(count)
                                .targetCount(100)
                                .columnCount(keys.size())
                                .columns(keys)
                                .primaryKeys(keys.contains("id") ? List.of("id") : List.of())
                                .foreignKeys(new ArrayList<>())
                                .uniqueKeys(new ArrayList<>())
                                .build());
                    }
                }
            } else {
                result.add(BulkDumpTableConfig.builder()
                        .tableName("dataset.json")
                        .currentCount(0)
                        .targetCount(100)
                        .columnCount(5)
                        .columns(List.of("id", "name", "email", "status", "createdAt"))
                        .primaryKeys(List.of("id"))
                        .foreignKeys(new ArrayList<>())
                        .uniqueKeys(new ArrayList<>())
                        .build());
            }
        } catch (Exception e) {
            log.error("Failed to discover JSON files at {}", resolvedPath, e);
            throw new RuntimeException("Failed to discover JSON files: " + e.getMessage(), e);
        }

        return result;
    }

    private List<BulkDumpTableConfig> discoverCsvTablesWithCounts(Long connectionId, String dirPath) {
        List<BulkDumpTableConfig> result = new ArrayList<>();
        String resolvedPath = dirPath;

        if (connectionId != null) {
            CosConnection cos = cosConnectionService.getConnection(connectionId);
            if (cos != null && "COS".equalsIgnoreCase(cos.getStorageType())) {
                try {
                    List<Map<String, Object>> objects = ibmCosService.listObjects(cos, dirPath);
                    List<String> csvFiles = objects.stream()
                            .map(o -> (String) o.get("name"))
                            .filter(n -> n != null && n.toLowerCase().endsWith(".csv"))
                            .toList();

                    for (String key : csvFiles) {
                        String name = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                        result.add(BulkDumpTableConfig.builder()
                                .tableName(name)
                                .currentCount(1)
                                .targetCount(100)
                                .columnCount(6)
                                .columns(List.of("id", "fullName", "email", "phone", "department", "status"))
                                .primaryKeys(List.of("id"))
                                .foreignKeys(new ArrayList<>())
                                .uniqueKeys(new ArrayList<>())
                                .build());
                    }
                    if (!result.isEmpty()) {
                        return result;
                    }
                } catch (Exception e) {
                    log.warn("Could not list COS objects for connection {}: {}", connectionId, e.getMessage());
                }
                return List.of(
                        BulkDumpTableConfig.builder().tableName("customers.csv").currentCount(0).targetCount(100).columnCount(6)
                                .columns(List.of("id", "fullName", "email", "phone", "city", "status")).primaryKeys(List.of("id")).build(),
                        BulkDumpTableConfig.builder().tableName("transactions.csv").currentCount(0).targetCount(100).columnCount(7)
                                .columns(List.of("id", "customerId", "amount", "currency", "status", "createdDate", "card")).primaryKeys(List.of("id")).build(),
                        BulkDumpTableConfig.builder().tableName("accounts.csv").currentCount(0).targetCount(100).columnCount(5)
                                .columns(List.of("id", "accountNumber", "balance", "department", "status")).primaryKeys(List.of("id")).build()
                );
            } else if (cos != null && "Local".equalsIgnoreCase(cos.getStorageType())) {
                resolvedPath = cos.getStorageLocation();
            } else {
                DbConnection dbConn = dbConnectionService.getConnection(connectionId);
                if (dbConn != null && "csv".equalsIgnoreCase(dbConn.getDbType())) {
                    resolvedPath = dbConn.getUrl();
                }
            }
        }

        if (resolvedPath == null || resolvedPath.trim().isEmpty()) {
            resolvedPath = "./data";
        }

        try {
            Path path = Paths.get(resolvedPath);
            if (Files.exists(path) && Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path, 1)) {
                    List<Path> csvFiles = walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                            .toList();

                    for (Path cf : csvFiles) {
                        String name = cf.getFileName().toString();
                        List<String> headers = com.enterprise.seedm.util.CsvUtils.readHeader(cf);
                        long count = com.enterprise.seedm.util.CsvUtils.countDataRows(cf);

                        result.add(BulkDumpTableConfig.builder()
                                .tableName(name)
                                .currentCount(count)
                                .targetCount(100)
                                .columnCount(headers.size())
                                .columns(headers)
                                .primaryKeys(headers.contains("id") ? List.of("id") : List.of())
                                .foreignKeys(new ArrayList<>())
                                .uniqueKeys(new ArrayList<>())
                                .build());
                    }
                }
            }
            if (result.isEmpty()) {
                result.add(BulkDumpTableConfig.builder()
                        .tableName("dataset.csv")
                        .currentCount(0)
                        .targetCount(100)
                        .columnCount(5)
                        .columns(List.of("id", "name", "email", "status", "createdAt"))
                        .primaryKeys(List.of("id"))
                        .foreignKeys(new ArrayList<>())
                        .uniqueKeys(new ArrayList<>())
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to discover CSV files at {}", resolvedPath, e);
            throw new RuntimeException("Failed to discover CSV files: " + e.getMessage(), e);
        }
    }

    /**
     * Step 6: Start dumping database tables based on the count.
     * Non-blocking asynchronous execution.
     */
    public BulkDumpProgress startBulkDump(String executionId, Long jobId, BulkDumpConfig config) {
        long totalTarget = (config.getTables() != null)
                ? config.getTables().stream().mapToLong(BulkDumpTableConfig::getTargetCount).sum()
                : 0;

        List<Map<String, Object>> initialTableProgress = new ArrayList<>();
        if (config.getTables() != null) {
            for (BulkDumpTableConfig tc : config.getTables()) {
                Map<String, Object> map = new ConcurrentHashMap<>();
                map.put("tableName", tc.getTableName());
                map.put("targetCount", tc.getTargetCount());
                map.put("insertedCount", 0L);
                map.put("status", "PENDING");
                initialTableProgress.add(map);
            }
        }

        BulkDumpProgress progress = BulkDumpProgress.builder()
                .executionId(executionId)
                .jobId(jobId)
                .dbType(config.getDbType())
                .status("RUNNING")
                .overallPercent(0)
                .totalTables(config.getTables() != null ? config.getTables().size() : 0)
                .processedTables(0)
                .totalTargetRecords(totalTarget)
                .totalInsertedRecords(0)
                .startTime(System.currentTimeMillis())
                .message("Initializing bulk dump process...")
                .tableProgress(initialTableProgress)
                .sampleDataReport(new ConcurrentHashMap<>())
                .logs(new ArrayList<>(List.of("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] Bulk dump process initiated")))
                .build();

        progressMap.put(executionId, progress);

        new Thread(() -> {
            try {
                executeBulkDump(executionId, config);
            } catch (Exception e) {
                log.error("Bulk dump execution failed for {}", executionId, e);
                progress.setStatus("FAILED");
                progress.setMessage("Failed: " + e.getMessage());
                progress.getLogs().add("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] Error: " + e.getMessage());
            }
        }, "bulk-dump-" + executionId).start();

        return progress;
    }

    private void executeBulkDump(String executionId, BulkDumpConfig config) {
        BulkDumpProgress progress = progressMap.get(executionId);
        String dbType = config.getDbType() != null ? config.getDbType().toLowerCase() : "sql";

        if (dbType.contains("mongo")) {
            executeMongoBulkDump(progress, config);
        } else if (dbType.contains("json")) {
            executeJsonBulkDump(progress, config);
        } else if (dbType.contains("csv")) {
            executeCsvBulkDump(progress, config);
        } else {
            executeSqlBulkDump(progress, config);
        }
    }

    // ==========================================
    // SQL BULK DUMP (With Integrity, PK/FK/Unique & Topological Sort)
    // ==========================================
    private void executeSqlBulkDump(BulkDumpProgress progress, BulkDumpConfig config) {
        DbConnection dbConn = dbConnectionService.getConnection(config.getConnectionId());
        if (dbConn == null) {
            throw new IllegalArgumentException("Connection not found: " + config.getConnectionId());
        }

        String targetSchema = (config.getSchema() != null && !config.getSchema().trim().isEmpty())
                ? config.getSchema().trim()
                : "public";

        progress.setMessage("Connecting to SQL database at " + dbConn.getUrl() + " (Schema: " + targetSchema + ")...");
        progress.getLogs().add("Connected to target database schema: " + targetSchema);

        try (Connection jdbcConn = DriverManager.getConnection(dbConn.getUrl(), dbConn.getUsername(), dbConn.getPassword())) {
            DatabaseMetaData metaData = jdbcConn.getMetaData();
            jdbcConn.setAutoCommit(false);

            String dbProdName = "";
            try {
                dbProdName = metaData.getDatabaseProductName().toLowerCase();
            } catch (Exception ignored) {}
            boolean isPostgres = dbProdName.contains("postgres");
            boolean isMysql = dbProdName.contains("mysql") || dbProdName.contains("mariadb");

            List<BulkDumpTableConfig> tables = config.getTables();
            if (tables == null || tables.isEmpty()) {
                progress.setStatus("COMPLETED");
                progress.setMessage("No tables selected for dump.");
                return;
            }

            // 1. Maintain Table Relationships: Topological Sorting (Parents before Children)
            List<BulkDumpTableConfig> sortedTables = sortTablesTopologically(metaData, targetSchema, tables);
            progress.getLogs().add("Verified table relationships & integrity constraints. Execution order: " +
                    sortedTables.stream().map(BulkDumpTableConfig::getTableName).collect(Collectors.joining(" -> ")));

            // 2. Map of generated & existing primary keys: parentTable.column -> list of IDs
            Map<String, List<Object>> generatedPrimaryKeys = new ConcurrentHashMap<>();

            int tableIndex = 0;
            for (BulkDumpTableConfig tableConfig : sortedTables) {
                tableIndex++;
                String tableName = tableConfig.getTableName();
                long targetCount = tableConfig.getTargetCount();

                progress.setCurrentTable(tableName);
                progress.setCurrentTableTarget(targetCount);
                progress.setCurrentTableInserted(0);
                progress.setCurrentTablePercent(0);
                progress.setMessage("Dumping table (" + tableIndex + "/" + sortedTables.size() + "): " + tableName + " [Target: " + targetCount + " rows]");
                updateTableProgress(progress, tableName, "RUNNING", 0);

                // Discover detailed column metadata
                List<ColumnDetail> columnDetails = getSqlColumnDetails(metaData, targetSchema, tableName);
                if (columnDetails.isEmpty()) {
                    progress.getLogs().add("Warning: No columns found for table " + tableName + ". Skipping.");
                    updateTableProgress(progress, tableName, "COMPLETED", 0);
                    continue;
                }

                // Pre-load existing PKs for referential integrity if foreign keys exist
                preloadExistingPrimaryKeys(jdbcConn, targetSchema, tableName, columnDetails, generatedPrimaryKeys);

                // Filter insertable columns (exclude auto-increment identity if DB auto-generates)
                List<ColumnDetail> insertColumns = columnDetails.stream()
                        .filter(c -> !c.isAutoIncrement)
                        .collect(Collectors.toList());
                if (insertColumns.isEmpty()) {
                    insertColumns = columnDetails;
                }

                String colNamesJoined = insertColumns.stream()
                        .map(c -> "\"" + c.name + "\"")
                        .collect(Collectors.joining(", "));

                // Handle PostgreSQL tsvector placeholders with to_tsvector('english', coalesce(?::text, ''))
                String placeholders = insertColumns.stream()
                        .map(c -> {
                            if (c.typeName != null && c.typeName.toLowerCase().contains("tsvector")) {
                                return "to_tsvector('english', coalesce(?::text, ''))";
                            }
                            return "?";
                        })
                        .collect(Collectors.joining(", "));

                String insertSql = String.format("INSERT INTO \"%s\".\"%s\" (%s) VALUES (%s)",
                        targetSchema, tableName, colNamesJoined, placeholders);

                if (isPostgres) {
                    insertSql += " ON CONFLICT DO NOTHING";
                } else if (isMysql) {
                    insertSql = insertSql.replaceFirst("(?i)INSERT INTO", "INSERT IGNORE INTO");
                }

                // Check for composite primary keys in this table
                List<ColumnDetail> pkColsInTable = insertColumns.stream()
                        .filter(c -> c.isPrimaryKey)
                        .collect(Collectors.toList());
                boolean isCompositePk = pkColsInTable.size() > 1;

                // Preload existing composite PKs to prevent duplicate combinations
                Set<String> usedCompositeKeys = Collections.synchronizedSet(new HashSet<>());
                if (isCompositePk) {
                    String pkColsJoined = pkColsInTable.stream()
                            .map(c -> "\"" + c.name + "\"")
                            .collect(Collectors.joining(", "));
                    String query = String.format("SELECT %s FROM \"%s\".\"%s\" LIMIT 10000",
                            pkColsJoined, targetSchema, tableName);
                    try (Statement stmt = jdbcConn.createStatement();
                         ResultSet rs = stmt.executeQuery(query)) {
                        while (rs.next()) {
                            StringBuilder sb = new StringBuilder();
                            for (int k = 1; k <= pkColsInTable.size(); k++) {
                                if (k > 1) sb.append("::");
                                sb.append(rs.getObject(k));
                            }
                            usedCompositeKeys.add(sb.toString());
                        }
                    } catch (Exception ignored) {}
                }

                // Preload and track used unique values for unique columns (e.g. store.manager_staff_id)
                Map<String, Set<Object>> usedUniqueValues = new ConcurrentHashMap<>();
                for (ColumnDetail col : columnDetails) {
                    if (col.isUnique) {
                        Set<Object> usedSet = ConcurrentHashMap.newKeySet();
                        usedUniqueValues.put(col.name.toLowerCase(), usedSet);
                        String query = String.format("SELECT \"%s\" FROM \"%s\".\"%s\" WHERE \"%s\" IS NOT NULL LIMIT 2000",
                                col.name, targetSchema, tableName, col.name);
                        try (Statement stmt = jdbcConn.createStatement();
                             ResultSet rs = stmt.executeQuery(query)) {
                            while (rs.next()) {
                                Object uVal = rs.getObject(1);
                                if (uVal != null) usedSet.add(uVal);
                            }
                        } catch (Exception ignored) {}
                    }
                }

                long inserted = 0;
                int batchSize = 100;
                List<Map<String, Object>> sampleRows = new ArrayList<>();

                try (PreparedStatement pstmt = jdbcConn.prepareStatement(insertSql)) {
                    while (inserted < targetCount) {
                        int currentBatch = (int) Math.min(batchSize, targetCount - inserted);

                        for (int i = 0; i < currentBatch; i++) {
                            long rowSeq = inserted + i + 1;
                            Map<String, Object> sampleRow = (sampleRows.size() < 5) ? new LinkedHashMap<>() : null;
                            Map<String, Object> rowValues = new HashMap<>();

                            // If composite PK, pre-generate unique composite PK combination
                            if (isCompositePk) {
                                generateCompositePkValues(pkColsInTable, rowSeq, tableName, generatedPrimaryKeys, usedCompositeKeys, rowValues);
                            }

                            for (int colIdx = 0; colIdx < insertColumns.size(); colIdx++) {
                                ColumnDetail col = insertColumns.get(colIdx);
                                Object val;
                                if (rowValues.containsKey(col.name.toLowerCase())) {
                                    val = rowValues.get(col.name.toLowerCase());
                                } else {
                                    val = generateValueForColumn(col, rowSeq, tableName, generatedPrimaryKeys, usedUniqueValues);
                                    rowValues.put(col.name.toLowerCase(), val);
                                }

                                setPreparedStatementValue(pstmt, colIdx + 1, col, val);

                                // If this column is a Primary Key, record it for child foreign keys
                                if (col.isPrimaryKey && val != null) {
                                    String pkLookupKey = tableName.toLowerCase() + "." + col.name.toLowerCase();
                                    generatedPrimaryKeys.computeIfAbsent(pkLookupKey, k -> new CopyOnWriteArrayList<>()).add(val);
                                }

                                if (sampleRow != null) {
                                    sampleRow.put(col.name, val != null ? (val instanceof byte[] ? "[binary bytea]" : val.toString()) : null);
                                }
                            }

                            pstmt.addBatch();
                            if (sampleRow != null) {
                                sampleRows.add(sampleRow);
                            }
                        }

                        pstmt.executeBatch();
                        jdbcConn.commit();

                        inserted += currentBatch;
                        progress.setCurrentTableInserted(inserted);
                        progress.setTotalInsertedRecords(progress.getTotalInsertedRecords() + currentBatch);

                        int tablePct = (int) Math.round(((double) inserted / targetCount) * 100);
                        progress.setCurrentTablePercent(tablePct);
                        updateTableProgress(progress, tableName, "RUNNING", inserted);

                        int overall = calculateOverallPercent(progress);
                        progress.setOverallPercent(overall);

                        try { Thread.sleep(25); } catch (InterruptedException ignored) {}
                    }

                    // Refresh primary keys from table so dependent child tables have all new IDs (including auto-inc serials)
                    refreshPrimaryKeys(jdbcConn, targetSchema, tableName, columnDetails, generatedPrimaryKeys);

                    progress.getSampleDataReport().put(tableName, sampleRows);
                    updateTableProgress(progress, tableName, "COMPLETED", inserted);
                    progress.setProcessedTables(progress.getProcessedTables() + 1);
                    progress.getLogs().add("Table `" + tableName + "`: successfully inserted " + inserted + " rows maintaining PK/FK constraints.");

                } catch (Exception ex) {
                    jdbcConn.rollback();
                    log.error("Failed to dump table {}", tableName, ex);
                    updateTableProgress(progress, tableName, "FAILED", inserted);
                    progress.getLogs().add("Error in table `" + tableName + "`: " + ex.getMessage());
                }
            }

            progress.setStatus("COMPLETED");
            progress.setEndTime(System.currentTimeMillis());
            progress.setOverallPercent(100);
            progress.setMessage("SQL Bulk data dump completed successfully with full relational integrity!");
            progress.getLogs().add("Bulk dump finished. Total rows dumped: " + progress.getTotalInsertedRecords());

        } catch (Exception e) {
            log.error("SQL Bulk dump error", e);
            throw new RuntimeException("SQL Bulk dump failed: " + e.getMessage(), e);
        }
    }

    private List<BulkDumpTableConfig> sortTablesTopologically(DatabaseMetaData metaData, String schema, List<BulkDumpTableConfig> tables) {
        if (tables == null || tables.size() <= 1) return tables;

        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, BulkDumpTableConfig> tableConfigMap = new HashMap<>();
        for (BulkDumpTableConfig tc : tables) {
            String name = tc.getTableName().toLowerCase();
            tableConfigMap.put(name, tc);
            dependencies.put(name, new HashSet<>());
        }

        for (BulkDumpTableConfig tc : tables) {
            String name = tc.getTableName().toLowerCase();
            try (ResultSet fkRs = metaData.getImportedKeys(null, schema, tc.getTableName())) {
                while (fkRs.next()) {
                    String parent = fkRs.getString("PKTABLE_NAME");
                    if (parent != null) {
                        parent = parent.toLowerCase();
                        if (tableConfigMap.containsKey(parent) && !parent.equalsIgnoreCase(name)) {
                            dependencies.get(name).add(parent);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not inspect FK dependencies for table {}: {}", tc.getTableName(), e.getMessage());
            }
        }

        List<BulkDumpTableConfig> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        int maxPasses = tables.size() * 3;
        int pass = 0;
        while (sorted.size() < tables.size() && pass++ < maxPasses) {
            boolean progressed = false;
            for (BulkDumpTableConfig tc : tables) {
                String name = tc.getTableName().toLowerCase();
                if (!visited.contains(name)) {
                    boolean ready = true;
                    for (String dep : dependencies.get(name)) {
                        if (!visited.contains(dep)) {
                            ready = false;
                            break;
                        }
                    }
                    if (ready) {
                        visited.add(name);
                        sorted.add(tc);
                        progressed = true;
                    }
                }
            }
            if (!progressed) {
                for (BulkDumpTableConfig tc : tables) {
                    String name = tc.getTableName().toLowerCase();
                    if (!visited.contains(name)) {
                        visited.add(name);
                        sorted.add(tc);
                    }
                }
                break;
            }
        }

        return sorted;
    }

    private void preloadExistingPrimaryKeys(Connection jdbcConn, String schema, String tableName,
                                           List<ColumnDetail> columns, Map<String, List<Object>> generatedPrimaryKeys) {
        // 1. Preload PKs of this table
        for (ColumnDetail col : columns) {
            if (col.isPrimaryKey) {
                String key = tableName.toLowerCase() + "." + col.name.toLowerCase();
                List<Object> existing = generatedPrimaryKeys.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
                if (existing.isEmpty()) {
                    String query = String.format("SELECT \"%s\" FROM \"%s\".\"%s\" WHERE \"%s\" IS NOT NULL LIMIT 1000",
                            col.name, schema, tableName, col.name);
                    try (Statement stmt = jdbcConn.createStatement();
                         ResultSet rs = stmt.executeQuery(query)) {
                        while (rs.next()) {
                            Object val = rs.getObject(1);
                            if (val != null && !existing.contains(val)) {
                                existing.add(val);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // 2. Preload PKs of parent tables referenced by FK columns
        for (ColumnDetail col : columns) {
            if (col.foreignTable != null && col.foreignColumn != null) {
                String fkKey = col.foreignTable.toLowerCase() + "." + col.foreignColumn.toLowerCase();
                List<Object> existing = generatedPrimaryKeys.computeIfAbsent(fkKey, k -> new CopyOnWriteArrayList<>());
                if (existing.isEmpty()) {
                    String query = String.format("SELECT \"%s\" FROM \"%s\".\"%s\" WHERE \"%s\" IS NOT NULL LIMIT 1000",
                            col.foreignColumn, schema, col.foreignTable, col.foreignColumn);
                    try (Statement stmt = jdbcConn.createStatement();
                         ResultSet rs = stmt.executeQuery(query)) {
                        while (rs.next()) {
                            Object val = rs.getObject(1);
                            if (val != null && !existing.contains(val)) {
                                existing.add(val);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } else if (col.name != null && col.name.toLowerCase().endsWith("_id") && !col.isPrimaryKey) {
                String assumedParent = col.name.toLowerCase().substring(0, col.name.length() - 3);
                String fkKey = assumedParent + "." + col.name.toLowerCase();
                List<Object> existing = generatedPrimaryKeys.computeIfAbsent(fkKey, k -> new CopyOnWriteArrayList<>());
                if (existing.isEmpty()) {
                    String query = String.format("SELECT \"%s\" FROM \"%s\".\"%s\" WHERE \"%s\" IS NOT NULL LIMIT 1000",
                            col.name, schema, assumedParent, col.name);
                    try (Statement stmt = jdbcConn.createStatement();
                         ResultSet rs = stmt.executeQuery(query)) {
                        while (rs.next()) {
                            Object val = rs.getObject(1);
                            if (val != null && !existing.contains(val)) {
                                existing.add(val);
                            }
                        }
                        if (!existing.isEmpty()) {
                            col.foreignTable = assumedParent;
                            col.foreignColumn = col.name;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void refreshPrimaryKeys(Connection jdbcConn, String schema, String tableName,
                                    List<ColumnDetail> columns, Map<String, List<Object>> generatedPrimaryKeys) {
        for (ColumnDetail col : columns) {
            if (col.isPrimaryKey) {
                String key = tableName.toLowerCase() + "." + col.name.toLowerCase();
                List<Object> list = generatedPrimaryKeys.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
                Set<Object> currentSet = new HashSet<>(list);
                String query = String.format("SELECT \"%s\" FROM \"%s\".\"%s\" WHERE \"%s\" IS NOT NULL ORDER BY \"%s\" DESC LIMIT 1000",
                        col.name, schema, tableName, col.name, col.name);
                try (Statement stmt = jdbcConn.createStatement();
                     ResultSet rs = stmt.executeQuery(query)) {
                    while (rs.next()) {
                        Object val = rs.getObject(1);
                        if (val != null && !currentSet.contains(val)) {
                            list.add(val);
                            currentSet.add(val);
                        }
                    }
                } catch (Exception ex) {
                    try (Statement stmt = jdbcConn.createStatement();
                         ResultSet rs = stmt.executeQuery(String.format("SELECT \"%s\" FROM \"%s\".\"%s\" WHERE \"%s\" IS NOT NULL LIMIT 1000",
                                 col.name, schema, tableName, col.name))) {
                        while (rs.next()) {
                            Object val = rs.getObject(1);
                            if (val != null && !currentSet.contains(val)) {
                                list.add(val);
                                currentSet.add(val);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void generateCompositePkValues(List<ColumnDetail> pkCols, long seq, String tableName,
                                           Map<String, List<Object>> generatedPrimaryKeys,
                                           Set<String> usedCompositeKeys, Map<String, Object> rowValues) {
        List<List<Object>> candidatesPerCol = new ArrayList<>();
        for (ColumnDetail pkCol : pkCols) {
            List<Object> candidates = null;
            if (pkCol.foreignTable != null && pkCol.foreignColumn != null) {
                String fkKey = pkCol.foreignTable.toLowerCase() + "." + pkCol.foreignColumn.toLowerCase();
                candidates = generatedPrimaryKeys.get(fkKey);
            }
            candidatesPerCol.add(candidates);
        }

        String chosenKey = null;
        List<Object> chosenVals = new ArrayList<>();
        for (int attempt = 0; attempt < 100; attempt++) {
            chosenVals.clear();
            StringBuilder keySb = new StringBuilder();
            for (int cIdx = 0; cIdx < pkCols.size(); cIdx++) {
                ColumnDetail pkCol = pkCols.get(cIdx);
                List<Object> candidates = candidatesPerCol.get(cIdx);
                Object v;
                if (candidates != null && !candidates.isEmpty()) {
                    v = candidates.get(random.nextInt(candidates.size()));
                } else {
                    if (pkCol.dataType == Types.SMALLINT) {
                        v = (int) ((seq * 31L + attempt * 7L + cIdx) % 32000) + 1;
                    } else if (pkCol.dataType == Types.TINYINT) {
                        v = (int) ((seq * 31L + attempt * 7L + cIdx) % 120) + 1;
                    } else {
                        v = (int) ((seq * 31L + attempt * 7L + cIdx) % 1000000) + 1;
                    }
                }
                chosenVals.add(v);
                if (cIdx > 0) keySb.append("::");
                keySb.append(v);
            }
            String ck = keySb.toString();
            if (!usedCompositeKeys.contains(ck)) {
                chosenKey = ck;
                break;
            }
        }

        if (chosenKey == null) {
            chosenVals.clear();
            StringBuilder keySb = new StringBuilder();
            for (int cIdx = 0; cIdx < pkCols.size(); cIdx++) {
                ColumnDetail pkCol = pkCols.get(cIdx);
                List<Object> candidates = candidatesPerCol.get(cIdx);
                Object v;
                if (candidates != null && !candidates.isEmpty()) {
                    v = candidates.get((int) ((seq + cIdx * 13) % candidates.size()));
                } else {
                    if (pkCol.dataType == Types.SMALLINT) {
                        v = (int) ((seq + cIdx) % 32000) + 1;
                    } else if (pkCol.dataType == Types.TINYINT) {
                        v = (int) ((seq + cIdx) % 120) + 1;
                    } else {
                        v = (seq + cIdx) * 1000L + 1;
                    }
                }
                chosenVals.add(v);
                if (cIdx > 0) keySb.append("::");
                keySb.append(v);
            }
            chosenKey = keySb.toString();
        }

        usedCompositeKeys.add(chosenKey);
        for (int cIdx = 0; cIdx < pkCols.size(); cIdx++) {
            rowValues.put(pkCols.get(cIdx).name.toLowerCase(), chosenVals.get(cIdx));
        }
    }

    // ==========================================
    // MONGODB BULK DUMP
    // ==========================================
    private void executeMongoBulkDump(BulkDumpProgress progress, BulkDumpConfig config) {
        String dbName = (config.getDatabase() != null && !config.getDatabase().trim().isEmpty())
                ? config.getDatabase().trim()
                : "test";

        progress.setMessage("Connecting to MongoDB database: " + dbName + "...");
        progress.getLogs().add("Connected to MongoDB database: " + dbName);

        try (MongoClient client = mongoConnectionHelper.createClient(config.getConnectionId())) {
            MongoDatabase db = client.getDatabase(dbName);
            List<BulkDumpTableConfig> collections = config.getTables();
            int collIndex = 0;

            for (BulkDumpTableConfig collConfig : collections) {
                collIndex++;
                String collName = collConfig.getTableName();
                long targetCount = collConfig.getTargetCount();

                progress.setCurrentTable(collName);
                progress.setCurrentTableTarget(targetCount);
                progress.setCurrentTableInserted(0);
                progress.setCurrentTablePercent(0);
                progress.setMessage("Dumping collection (" + collIndex + "/" + collections.size() + "): " + collName + " [Target: " + targetCount + " docs]");
                updateTableProgress(progress, collName, "RUNNING", 0);

                MongoCollection<Document> collection = db.getCollection(collName);

                Document sampleDoc = collection.find().first();
                Set<String> fieldNames = new LinkedHashSet<>();
                Map<String, Integer> fieldMaxLengths = new HashMap<>();

                if (sampleDoc != null) {
                    for (String key : sampleDoc.keySet()) {
                        if (!"_id".equals(key)) {
                            fieldNames.add(key);
                            Object val = sampleDoc.get(key);
                            if (val instanceof String) {
                                int slen = ((String) val).length();
                                if (slen > 0 && slen <= 5) {
                                    fieldMaxLengths.put(key, slen);
                                }
                            }
                        }
                    }
                }
                if (collConfig.getColumns() != null) {
                    for (String colStr : collConfig.getColumns()) {
                        if (colStr == null || colStr.trim().isEmpty() || "_id".equalsIgnoreCase(colStr.trim())) continue;
                        String raw = colStr.trim();
                        String cleanName = raw;
                        Integer parsedLen = null;
                        if (raw.contains("(") && raw.contains(")")) {
                            int p1 = raw.indexOf('(');
                            int p2 = raw.indexOf(')', p1);
                            try {
                                parsedLen = Integer.parseInt(raw.substring(p1 + 1, p2).trim());
                            } catch (Exception ignored) {}
                        }
                        if (raw.contains(":") || raw.contains(" ")) {
                            String[] parts = raw.split("[: ]+");
                            cleanName = parts[0];
                        }
                        if (cleanName.contains("(")) {
                            cleanName = cleanName.substring(0, cleanName.indexOf('(')).trim();
                        }
                        fieldNames.add(cleanName);
                        if (parsedLen != null && parsedLen > 0) {
                            fieldMaxLengths.put(cleanName, parsedLen);
                        }
                    }
                }
                if (fieldNames.isEmpty()) {
                    fieldNames.addAll(List.of("name", "email", "phone", "status", "role", "amount", "createdAt"));
                }

                long inserted = 0;
                int batchSize = 100;
                List<Map<String, Object>> sampleDocs = new ArrayList<>();

                while (inserted < targetCount) {
                    int currentBatch = (int) Math.min(batchSize, targetCount - inserted);
                    List<Document> batch = new ArrayList<>(currentBatch);

                    for (int i = 0; i < currentBatch; i++) {
                        long rowSeq = inserted + i + 1;
                        Document doc = new Document();
                        doc.put("_id", new ObjectId());

                        Map<String, Object> sample = (sampleDocs.size() < 5) ? new LinkedHashMap<>() : null;
                        if (sample != null) {
                            sample.put("_id", doc.get("_id").toString());
                        }

                        for (String f : fieldNames) {
                            Object val = generateMongoValueForField(f, rowSeq, fieldMaxLengths.get(f));
                            doc.put(f, val);
                            if (sample != null) {
                                sample.put(f, val != null ? val.toString() : null);
                            }
                        }

                        batch.add(doc);
                        if (sample != null) {
                            sampleDocs.add(sample);
                        }
                    }

                    collection.insertMany(batch);

                    inserted += currentBatch;
                    progress.setCurrentTableInserted(inserted);
                    progress.setTotalInsertedRecords(progress.getTotalInsertedRecords() + currentBatch);

                    int pct = (int) Math.round(((double) inserted / targetCount) * 100);
                    progress.setCurrentTablePercent(pct);
                    updateTableProgress(progress, collName, "RUNNING", inserted);

                    int overall = calculateOverallPercent(progress);
                    progress.setOverallPercent(overall);

                    try { Thread.sleep(25); } catch (InterruptedException ignored) {}
                }

                progress.getSampleDataReport().put(collName, sampleDocs);
                updateTableProgress(progress, collName, "COMPLETED", inserted);
                progress.setProcessedTables(progress.getProcessedTables() + 1);
                progress.getLogs().add("Collection `" + collName + "`: successfully dumped " + inserted + " documents.");
            }

            progress.setStatus("COMPLETED");
            progress.setEndTime(System.currentTimeMillis());
            progress.setOverallPercent(100);
            progress.setMessage("MongoDB bulk dump completed successfully!");
            progress.getLogs().add("Bulk dump finished. Total documents dumped: " + progress.getTotalInsertedRecords());

        } catch (Exception e) {
            log.error("MongoDB bulk dump error", e);
            throw new RuntimeException("MongoDB bulk dump failed: " + e.getMessage(), e);
        }
    }

    // ==========================================
    // JSON BULK DUMP (Local Storage or COS)
    // ==========================================
    private void executeJsonBulkDump(BulkDumpProgress progress, BulkDumpConfig config) {
        boolean isCos = ("COS".equalsIgnoreCase(config.getStorageType()) || config.getCosId() != null);
        CosConnection cosConn = null;
        if (isCos && config.getCosId() != null) {
            cosConn = cosConnectionService.getConnection(config.getCosId());
        }

        String targetLocation = config.getDirPath();
        if (targetLocation == null || targetLocation.trim().isEmpty()) {
            targetLocation = isCos ? "bulk-dump" : "./data/bulk_dump";
        }

        if (isCos && cosConn != null) {
            progress.setMessage("Targeting IBM Cloud Object Storage (Bucket: " + cosConn.getBucketName() + ", Prefix: " + targetLocation + ")...");
            progress.getLogs().add("Target COS Bucket: " + cosConn.getBucketName() + " | Prefix: " + targetLocation);
        } else {
            if (config.getConnectionId() != null && (config.getDirPath() == null || config.getDirPath().trim().isEmpty() || "./data/bulk_dump".equals(config.getDirPath()))) {
                CosConnection localCos = cosConnectionService.getConnection(config.getConnectionId());
                if (localCos != null && "Local".equalsIgnoreCase(localCos.getStorageType()) && localCos.getStorageLocation() != null && !localCos.getStorageLocation().trim().isEmpty()) {
                    targetLocation = localCos.getStorageLocation();
                }
            }
            progress.setMessage("Targeting Local Storage Directory: " + targetLocation + "...");
            progress.getLogs().add("Target Local JSON Directory: " + targetLocation);
            File dir = new File(targetLocation);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }

        try {
            List<BulkDumpTableConfig> tables = config.getTables();
            int fileIndex = 0;

            for (BulkDumpTableConfig tableConfig : tables) {
                fileIndex++;
                String rawName = tableConfig.getTableName();
                String baseName = rawName.toLowerCase().endsWith(".json")
                        ? rawName.substring(0, rawName.length() - 5)
                        : rawName;
                Matcher numMatcher = Pattern.compile("^(.*?)[-_]\\d+$").matcher(baseName);
                if (numMatcher.matches()) {
                    baseName = numMatcher.group(1);
                }

                long targetCount = tableConfig.getTargetCount();

                progress.setCurrentTable(rawName);
                progress.setCurrentTableTarget(targetCount);
                progress.setCurrentTableInserted(0);
                progress.setCurrentTablePercent(0);
                progress.setMessage("Dumping JSON dataset (" + fileIndex + "/" + tables.size() + "): " + rawName + " [Target: " + targetCount + " files]");
                updateTableProgress(progress, rawName, "RUNNING", 0);

                List<String> fields = (tableConfig.getColumns() != null && !tableConfig.getColumns().isEmpty())
                        ? tableConfig.getColumns()
                        : List.of("id", "fullName", "email", "phone", "department", "status", "salary", "createdDate");

                JsonNode templateNode = findTemplateJsonNode(config, rawName);
                List<Map<String, Object>> sampleRows = new ArrayList<>();

                long inserted = 0;
                long lastReported = 0;
                while (inserted < targetCount) {
                    long rowSeq = inserted + 1;
                    String recordFileName = (targetCount == 1) ? (baseName + ".json") : (baseName + "_" + rowSeq + ".json");

                    JsonNode recordNode = (templateNode != null)
                            ? buildRecordFromTemplate(templateNode, rowSeq)
                            : buildRecordFromFields(fields, rowSeq);

                    // Output to COS or Local storage for each individual JSON file
                    if (isCos && cosConn != null) {
                        File tempFile = File.createTempFile("bulk-dump-", "-" + recordFileName);
                        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, recordNode);
                        String objectKey = targetLocation.endsWith("/") ? targetLocation + recordFileName : targetLocation + "/" + recordFileName;
                        ibmCosService.uploadFile(cosConn, objectKey, tempFile.toPath());
                        tempFile.delete();
                    } else {
                        File outputFile = new File(targetLocation, recordFileName);
                        if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
                        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, recordNode);
                    }

                    // Collect sample rows (up to 5) for UI report
                    if (sampleRows.size() < 5 && recordNode.isObject()) {
                        Map<String, Object> sample = new LinkedHashMap<>();
                        recordNode.fieldNames().forEachRemaining(fn -> {
                            JsonNode fnVal = recordNode.get(fn);
                            sample.put(fn, fnVal.isTextual() ? fnVal.asText() : (fnVal.isObject() || fnVal.isArray() ? fnVal.toString() : fnVal.asText()));
                        });
                        sampleRows.add(sample);
                    }

                    inserted++;
                    if (inserted % 100 == 0 || inserted == targetCount) {
                        long delta = inserted - lastReported;
                        lastReported = inserted;
                        progress.setCurrentTableInserted(inserted);
                        progress.setTotalInsertedRecords(progress.getTotalInsertedRecords() + delta);

                        int pct = (int) Math.round(((double) inserted / targetCount) * 100);
                        progress.setCurrentTablePercent(pct);
                        updateTableProgress(progress, rawName, "RUNNING", inserted);

                        int overall = calculateOverallPercent(progress);
                        progress.setOverallPercent(overall);

                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    }
                }

                if (isCos && cosConn != null) {
                    progress.getLogs().add("Dataset `" + rawName + "`: successfully uploaded " + inserted + " individual JSON files to COS bucket [" + cosConn.getBucketName() + "] prefix: " + targetLocation);
                } else {
                    progress.getLogs().add("Dataset `" + rawName + "`: successfully dumped " + inserted + " individual JSON files to directory: " + targetLocation);
                }

                progress.getSampleDataReport().put(rawName, sampleRows);
                updateTableProgress(progress, rawName, "COMPLETED", inserted);
                progress.setProcessedTables(progress.getProcessedTables() + 1);
            }

            progress.setStatus("COMPLETED");
            progress.setEndTime(System.currentTimeMillis());
            progress.setOverallPercent(100);
            progress.setMessage("JSON bulk dump completed successfully!");
            progress.getLogs().add("Bulk dump finished. Total JSON files dumped: " + progress.getTotalInsertedRecords());

        } catch (Exception e) {
            log.error("JSON bulk dump error", e);
            throw new RuntimeException("JSON bulk dump failed: " + e.getMessage(), e);
        }
    }

    private void executeCsvBulkDump(BulkDumpProgress progress, BulkDumpConfig config) {
        boolean isCos = ("COS".equalsIgnoreCase(config.getStorageType()) || config.getCosId() != null);
        CosConnection cosConn = null;
        if (isCos && config.getCosId() != null) {
            cosConn = cosConnectionService.getConnection(config.getCosId());
        }

        String targetLocation = config.getDirPath();
        if (targetLocation == null || targetLocation.trim().isEmpty()) {
            targetLocation = isCos ? "bulk-dump" : "./data/bulk_dump";
        }

        if (isCos && cosConn != null) {
            progress.setMessage("Targeting IBM Cloud Object Storage (Bucket: " + cosConn.getBucketName() + ", Prefix: " + targetLocation + ")...");
            progress.getLogs().add("Target COS Bucket: " + cosConn.getBucketName() + " | Prefix: " + targetLocation);
        } else {
            if (config.getConnectionId() != null && (config.getDirPath() == null || config.getDirPath().trim().isEmpty() || "./data/bulk_dump".equals(config.getDirPath()))) {
                CosConnection localCos = cosConnectionService.getConnection(config.getConnectionId());
                if (localCos != null && "Local".equalsIgnoreCase(localCos.getStorageType()) && localCos.getStorageLocation() != null && !localCos.getStorageLocation().trim().isEmpty()) {
                    targetLocation = localCos.getStorageLocation();
                }
            }
            progress.setMessage("Targeting Local Storage Directory: " + targetLocation + "...");
            progress.getLogs().add("Target Local CSV Directory: " + targetLocation);
            File dir = new File(targetLocation);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }

        try {
            List<BulkDumpTableConfig> tables = config.getTables();
            int fileIndex = 0;

            for (BulkDumpTableConfig tableConfig : tables) {
                fileIndex++;
                String rawName = tableConfig.getTableName();
                String outputFileName = rawName.toLowerCase().endsWith(".csv")
                        ? rawName
                        : (rawName + ".csv");

                long targetCount = tableConfig.getTargetCount();

                progress.setCurrentTable(rawName);
                progress.setCurrentTableTarget(targetCount);
                progress.setCurrentTableInserted(0);
                progress.setCurrentTablePercent(0);
                progress.setMessage("Dumping CSV dataset (" + fileIndex + "/" + tables.size() + "): " + outputFileName + " [Target: " + targetCount + " rows]");
                updateTableProgress(progress, rawName, "RUNNING", 0);

                List<String> fields = (tableConfig.getColumns() != null && !tableConfig.getColumns().isEmpty())
                        ? tableConfig.getColumns()
                        : null;

                if (fields == null || fields.isEmpty()) {
                    File templateFile = new File(targetLocation, rawName);
                    if (templateFile.exists() && templateFile.isFile()) {
                        try {
                            fields = com.enterprise.seedm.util.CsvUtils.readHeader(templateFile.toPath());
                        } catch (Exception ignored) {}
                    }
                }

                if (fields == null || fields.isEmpty()) {
                    fields = List.of("id", "fullName", "email", "phone", "department", "status", "salary", "createdDate");
                }

                List<Map<String, Object>> sampleRows = new ArrayList<>();
                long inserted = 0;
                long lastReported = 0;

                File localTargetFile = null;
                File cosTempFile = null;
                BufferedWriter writer = null;

                try {
                    if (isCos && cosConn != null) {
                        cosTempFile = File.createTempFile("bulk-dump-", "-" + outputFileName);
                        writer = Files.newBufferedWriter(cosTempFile.toPath(), StandardCharsets.UTF_8);
                    } else {
                        localTargetFile = targetLocation.toLowerCase().endsWith(".csv")
                                ? new File(targetLocation)
                                : new File(targetLocation, outputFileName);
                        if (localTargetFile.getParentFile() != null) {
                            localTargetFile.getParentFile().mkdirs();
                        }
                        writer = Files.newBufferedWriter(localTargetFile.toPath(), StandardCharsets.UTF_8);
                    }

                    // Write CSV header once at the top of the file
                    writer.write(com.enterprise.seedm.util.CsvUtils.formatLine(fields));
                    writer.write("\n");

                    while (inserted < targetCount) {
                        long rowSeq = inserted + 1;
                        List<String> values = new ArrayList<>();
                        Map<String, Object> sample = (sampleRows.size() < 5) ? new LinkedHashMap<>() : null;

                        for (String field : fields) {
                            Object val = generateJsonValueForField(field, rowSeq);
                            String strVal = (val != null) ? val.toString() : "";
                            values.add(strVal);
                            if (sample != null) {
                                sample.put(field, strVal);
                            }
                        }

                        if (sample != null) {
                            sampleRows.add(sample);
                        }

                        writer.write(com.enterprise.seedm.util.CsvUtils.formatLine(values));
                        writer.write("\n");

                        inserted++;
                        if (inserted % 100 == 0 || inserted == targetCount) {
                            writer.flush();
                            long delta = inserted - lastReported;
                            lastReported = inserted;
                            progress.setCurrentTableInserted(inserted);
                            progress.setTotalInsertedRecords(progress.getTotalInsertedRecords() + delta);

                            int pct = (int) Math.round(((double) inserted / targetCount) * 100);
                            progress.setCurrentTablePercent(pct);
                            updateTableProgress(progress, rawName, "RUNNING", inserted);

                            int overall = calculateOverallPercent(progress);
                            progress.setOverallPercent(overall);

                            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                        }
                    }

                    writer.flush();
                } finally {
                    if (writer != null) {
                        try { writer.close(); } catch (Exception ignored) {}
                    }
                }

                if (isCos && cosConn != null && cosTempFile != null && cosTempFile.exists()) {
                    try {
                        String objectKey = targetLocation.endsWith("/") ? targetLocation + outputFileName : targetLocation + "/" + outputFileName;
                        ibmCosService.uploadFile(cosConn, objectKey, cosTempFile.toPath());
                        progress.getLogs().add("Dataset `" + rawName + "`: successfully uploaded single CSV file with " + inserted + " rows to COS bucket [" + cosConn.getBucketName() + "] object: " + objectKey);
                    } finally {
                        cosTempFile.delete();
                    }
                } else {
                    progress.getLogs().add("Dataset `" + rawName + "`: successfully generated 1 CSV file with " + inserted + " rows -> " + (localTargetFile != null ? localTargetFile.getAbsolutePath() : outputFileName));
                }

                progress.getSampleDataReport().put(rawName, sampleRows);
                updateTableProgress(progress, rawName, "COMPLETED", inserted);
                progress.setProcessedTables(progress.getProcessedTables() + 1);
            }

            progress.setStatus("COMPLETED");
            progress.setEndTime(System.currentTimeMillis());
            progress.setOverallPercent(100);
            progress.setMessage("CSV bulk dump completed successfully!");
            progress.getLogs().add("Bulk dump finished. Total CSV files dumped: " + progress.getTotalInsertedRecords());

        } catch (Exception e) {
            log.error("CSV bulk dump error", e);
            throw new RuntimeException("CSV bulk dump failed: " + e.getMessage(), e);
        }
    }

    private JsonNode findTemplateJsonNode(BulkDumpConfig config, String tableName) {
        String baseName = tableName.toLowerCase().endsWith(".json")
                ? tableName.substring(0, tableName.length() - 5)
                : tableName;
        Matcher m = Pattern.compile("^(.*?)[-_]\\d+$").matcher(baseName);
        String prefix = m.matches() ? m.group(1) : baseName;

        List<String> candidatePaths = new ArrayList<>();
        if (config.getDirPath() != null && !config.getDirPath().trim().isEmpty()) {
            candidatePaths.add(config.getDirPath());
        }
        if (config.getConnectionId() != null) {
            try {
                CosConnection cos = cosConnectionService.getConnection(config.getConnectionId());
                if (cos != null && "Local".equalsIgnoreCase(cos.getStorageType()) && cos.getStorageLocation() != null) {
                    candidatePaths.add(cos.getStorageLocation());
                }
            } catch (Exception ignored) {}
            try {
                DbConnection dbConn = dbConnectionService.getConnection(config.getConnectionId());
                if (dbConn != null && "json".equalsIgnoreCase(dbConn.getDbType()) && dbConn.getUrl() != null) {
                    candidatePaths.add(dbConn.getUrl());
                }
            } catch (Exception ignored) {}
        }
        candidatePaths.add("./data");

        for (String cPath : candidatePaths) {
            try {
                Path p = Paths.get(cPath);
                if (Files.isDirectory(p)) {
                    Path f = p.resolve(tableName.endsWith(".json") ? tableName : tableName + ".json");
                    if (Files.isRegularFile(f)) {
                        JsonNode n = objectMapper.readTree(f.toFile());
                        if (n.isObject()) return n;
                        if (n.isArray() && n.size() > 0 && n.get(0).isObject()) return n.get(0);
                    }
                    Path f2 = p.resolve(prefix + ".json");
                    if (Files.isRegularFile(f2)) {
                        JsonNode n = objectMapper.readTree(f2.toFile());
                        if (n.isObject()) return n;
                        if (n.isArray() && n.size() > 0 && n.get(0).isObject()) return n.get(0);
                    }
                    try (Stream<Path> walk = Files.walk(p, 1)) {
                        Optional<Path> match = walk.filter(Files::isRegularFile)
                                .filter(cand -> cand.getFileName().toString().toLowerCase().startsWith(prefix.toLowerCase())
                                        && cand.getFileName().toString().toLowerCase().endsWith(".json"))
                                .findFirst();
                        if (match.isPresent()) {
                            JsonNode n = objectMapper.readTree(match.get().toFile());
                            if (n.isObject()) return n;
                            if (n.isArray() && n.size() > 0 && n.get(0).isObject()) return n.get(0);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private JsonNode buildRecordFromTemplate(JsonNode template, long rowSeq) {
        if (template == null || !template.isObject()) {
            return null;
        }
        ObjectNode result = objectMapper.createObjectNode();
        template.fieldNames().forEachRemaining(field -> {
            JsonNode child = template.get(field);
            if (child.isObject()) {
                result.set(field, buildRecordFromTemplate(child, rowSeq));
            } else if (child.isArray()) {
                ArrayNode arr = objectMapper.createArrayNode();
                for (JsonNode elem : child) {
                    if (elem.isObject()) {
                        arr.add(buildRecordFromTemplate(elem, rowSeq));
                    } else {
                        Object val = generateJsonValueForField(field, rowSeq);
                        putPrimitiveToArray(arr, val);
                    }
                }
                result.set(field, arr);
            } else {
                Object val = generateJsonValueForField(field, rowSeq);
                putPrimitiveToObject(result, field, val);
            }
        });
        return result;
    }

    private ObjectNode buildRecordFromFields(List<String> fields, long rowSeq) {
        ObjectNode result = objectMapper.createObjectNode();
        for (String field : fields) {
            Object val = generateJsonValueForField(field, rowSeq);
            putPrimitiveToObject(result, field, val);
        }
        return result;
    }

    private void putPrimitiveToObject(ObjectNode node, String field, Object val) {
        if (val == null) {
            node.putNull(field);
        } else if (val instanceof Long) {
            node.put(field, (Long) val);
        } else if (val instanceof Integer) {
            node.put(field, (Integer) val);
        } else if (val instanceof Double) {
            node.put(field, (Double) val);
        } else if (val instanceof Boolean) {
            node.put(field, (Boolean) val);
        } else {
            node.put(field, val.toString());
        }
    }

    private void putPrimitiveToArray(ArrayNode arr, Object val) {
        if (val == null) {
            arr.addNull();
        } else if (val instanceof Long) {
            arr.add((Long) val);
        } else if (val instanceof Integer) {
            arr.add((Integer) val);
        } else if (val instanceof Double) {
            arr.add((Double) val);
        } else if (val instanceof Boolean) {
            arr.add((Boolean) val);
        } else {
            arr.add(val.toString());
        }
    }

    // ==========================================
    // DATA GENERATION & CONSTRAINT CHECKING
    // ==========================================

    private void updateTableProgress(BulkDumpProgress progress, String tableName, String status, long count) {
        for (Map<String, Object> item : progress.getTableProgress()) {
            if (tableName.equalsIgnoreCase((String) item.get("tableName"))) {
                item.put("status", status);
                item.put("insertedCount", count);
                break;
            }
        }
    }

    private int calculateOverallPercent(BulkDumpProgress progress) {
        if (progress.getTotalTargetRecords() <= 0) return 0;
        int pct = (int) Math.round(((double) progress.getTotalInsertedRecords() / progress.getTotalTargetRecords()) * 100);
        return Math.min(pct, 99);
    }

    private static class ColumnDetail {
        String name;
        int dataType;
        String typeName;
        int size;
        int precision;
        int scale;
        boolean isNullable;
        boolean isAutoIncrement;
        boolean isPrimaryKey;
        boolean isUnique;
        String foreignTable;
        String foreignColumn;
    }

    private List<ColumnDetail> getSqlColumnDetails(DatabaseMetaData metaData, String schema, String table) throws SQLException {
        List<ColumnDetail> list = new ArrayList<>();

        // 1. Primary keys
        Set<String> pkCols = new HashSet<>();
        try (ResultSet pkRs = metaData.getPrimaryKeys(null, schema, table)) {
            while (pkRs.next()) {
                String col = pkRs.getString("COLUMN_NAME");
                if (col != null) pkCols.add(col.toLowerCase());
            }
        } catch (Exception ignored) {}

        // 2. Foreign keys
        Map<String, String[]> fkCols = new HashMap<>();
        try (ResultSet fkRs = metaData.getImportedKeys(null, schema, table)) {
            while (fkRs.next()) {
                String fkCol = fkRs.getString("FKCOLUMN_NAME");
                String pkTable = fkRs.getString("PKTABLE_NAME");
                String pkCol = fkRs.getString("PKCOLUMN_NAME");
                if (fkCol != null && pkTable != null && pkCol != null) {
                    fkCols.put(fkCol.toLowerCase(), new String[]{pkTable, pkCol});
                }
            }
        } catch (Exception ignored) {}

        // Fallback for foreign keys via information_schema if JDBC driver didn't report them
        if (fkCols.isEmpty()) {
            String fkQuery = "SELECT kcu.column_name, ccu.table_name AS foreign_table, ccu.column_name AS foreign_column " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                    "JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema " +
                    "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = ? AND tc.table_name = ?";
            try (PreparedStatement fkStmt = metaData.getConnection().prepareStatement(fkQuery)) {
                fkStmt.setString(1, schema);
                fkStmt.setString(2, table);
                try (ResultSet fkRs = fkStmt.executeQuery()) {
                    while (fkRs.next()) {
                        String fkCol = fkRs.getString("column_name");
                        String pkTable = fkRs.getString("foreign_table");
                        String pkCol = fkRs.getString("foreign_column");
                        if (fkCol != null && pkTable != null && pkCol != null) {
                            fkCols.put(fkCol.toLowerCase(), new String[]{pkTable, pkCol});
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Unique keys
        Set<String> uqCols = new HashSet<>();
        try (ResultSet idxRs = metaData.getIndexInfo(null, schema, table, true, false)) {
            while (idxRs.next()) {
                boolean nonUnique = idxRs.getBoolean("NON_UNIQUE");
                String col = idxRs.getString("COLUMN_NAME");
                if (!nonUnique && col != null && !pkCols.contains(col.toLowerCase())) {
                    uqCols.add(col.toLowerCase());
                }
            }
        } catch (Exception ignored) {}

        // 4. Columns
        try (ResultSet rs = metaData.getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                ColumnDetail cd = new ColumnDetail();
                cd.name = rs.getString("COLUMN_NAME");
                cd.dataType = rs.getInt("DATA_TYPE");
                cd.typeName = rs.getString("TYPE_NAME");
                cd.size = rs.getInt("COLUMN_SIZE");
                cd.precision = cd.size;
                try {
                    cd.scale = rs.getInt("DECIMAL_DIGITS");
                } catch (Exception ignored) {}
                cd.isNullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                try {
                    cd.isAutoIncrement = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
                } catch (Exception ignored) {}
                try {
                    String colDef = rs.getString("COLUMN_DEF");
                    if (colDef != null && colDef.toLowerCase().contains("nextval")) {
                        cd.isAutoIncrement = true;
                    }
                } catch (Exception ignored) {}

                // Parse size from typeName if not provided (e.g. "varchar(2)", "varry(2)", "varying(2)")
                if (cd.typeName != null) {
                    String lowerType = cd.typeName.toLowerCase();
                    if (lowerType.contains("(") && lowerType.contains(")")) {
                        try {
                            int start = lowerType.indexOf('(') + 1;
                            int end = lowerType.indexOf(')', start);
                            int parsedSize = Integer.parseInt(lowerType.substring(start, end).trim());
                            if (parsedSize > 0) {
                                cd.size = parsedSize;
                                cd.precision = parsedSize;
                            }
                        } catch (Exception ignored) {}
                    }
                    if (lowerType.contains("varchar") || lowerType.contains("varying") || lowerType.contains("varry")) {
                        if (cd.dataType == Types.OTHER || cd.dataType == 0) {
                            cd.dataType = Types.VARCHAR;
                        }
                    }
                    if (lowerType.contains("bytea") || lowerType.contains("blob")) {
                        cd.dataType = Types.VARBINARY;
                    }
                }

                String lowerCol = cd.name.toLowerCase();
                cd.isPrimaryKey = pkCols.contains(lowerCol);
                cd.isUnique = uqCols.contains(lowerCol);

                if (fkCols.containsKey(lowerCol)) {
                    cd.foreignTable = fkCols.get(lowerCol)[0];
                    cd.foreignColumn = fkCols.get(lowerCol)[1];
                }

                list.add(cd);
            }
        }
        return list;
    }

    private static final String[] COUNTRY_CODES = {"US", "GB", "DE", "FR", "IN", "CA", "AU", "JP", "SG", "NL", "CH", "ES", "IT", "SE", "AE", "BR", "MX", "ZA", "NZ", "IE"};
    private static final String[] STATE_CODES = {"NY", "CA", "TX", "FL", "IL", "PA", "OH", "GA", "NC", "MI", "NJ", "VA", "WA", "AZ", "MA", "TN", "IN", "MO", "MD", "WI"};

    private String generateCountryCode(long seq) {
        return COUNTRY_CODES[(int) (Math.max(0, seq - 1) % COUNTRY_CODES.length)];
    }

    private String generateStateCode(long seq) {
        return STATE_CODES[(int) (Math.max(0, seq - 1) % STATE_CODES.length)];
    }

    private String generateTwoCharCode(long seq) {
        long s = Math.max(0, seq - 1);
        char c1 = (char) ('A' + (s % 26));
        long rem = s / 26;
        char c2 = (rem % 36 < 10) ? (char) ('0' + (rem % 10)) : (char) ('A' + ((rem - 10) % 26));
        return "" + c1 + c2;
    }

    private String generateShortAlphaNum(long seq, int len) {
        if (len <= 0) len = 5;
        long s = Math.max(0, seq - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            long digit = s % 36;
            s /= 36;
            char ch = digit < 10 ? (char) ('0' + digit) : (char) ('A' + (digit - 10));
            sb.append(ch);
        }
        return sb.reverse().toString();
    }

    private Object generateValueForColumn(ColumnDetail col, long seq, String tableName, Map<String, List<Object>> generatedPrimaryKeys) {
        return generateValueForColumn(col, seq, tableName, generatedPrimaryKeys, null);
    }

    private Object generateValueForColumn(ColumnDetail col, long seq, String tableName,
                                         Map<String, List<Object>> generatedPrimaryKeys,
                                         Map<String, Set<Object>> usedUniqueValues) {
        String colName = col.name.toLowerCase();
        int type = col.dataType;
        String typeName = col.typeName != null ? col.typeName.toLowerCase() : "";

        // 1. Maintain Foreign Key Referential Integrity
        if (col.foreignTable != null && col.foreignColumn != null) {
            String fkLookupKey = col.foreignTable.toLowerCase() + "." + col.foreignColumn.toLowerCase();
            List<Object> parentKeys = generatedPrimaryKeys.get(fkLookupKey);
            if (parentKeys != null && !parentKeys.isEmpty()) {
                if (col.isUnique && usedUniqueValues != null) {
                    Set<Object> used = usedUniqueValues.get(colName);
                    if (used != null) {
                        List<Object> available = parentKeys.stream()
                                .filter(k -> !used.contains(k))
                                .collect(Collectors.toList());
                        if (!available.isEmpty()) {
                            Object chosen = available.get(random.nextInt(available.size()));
                            used.add(chosen);
                            return chosen;
                        }
                        if (col.isNullable) return null;
                    }
                }
                return parentKeys.get(random.nextInt(parentKeys.size()));
            }
            if (col.isNullable && random.nextBoolean()) {
                return null;
            }
            // Fallback for foreign key if parent table empty
            if (col.size > 0 && col.size <= 2) {
                return "R" + (seq % 10);
            }
            return (type == Types.BIGINT || type == Types.INTEGER || type == Types.SMALLINT)
                    ? 1L : (col.size > 0 && col.size < 5 ? "R" + (seq % 9 + 1) : "REF-1");
        }

        // 2. Primary Key / Unique constraints: guarantee uniqueness while matching exact data type
        if (col.isPrimaryKey || col.isUnique) {
            // Date / Time unique constraints (e.g. rental.rental_date)
            if (type == Types.DATE) {
                return java.sql.Date.valueOf(LocalDate.now().minusDays(seq % 7300));
            }
            if (type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE || type == Types.TIME
                    || typeName.contains("timestamp") || typeName.contains("time")
                    || colName.contains("date") || colName.contains("time")) {
                return java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(seq * 5L + (seq % 60)));
            }

            // Numeric unique constraints
            if (type == Types.BIGINT) return seq;
            if (type == Types.SMALLINT) return (int) (Math.abs(seq) % 32000) + 1;
            if (type == Types.TINYINT) return (int) (Math.abs(seq) % 120) + 1;
            if (type == Types.INTEGER) return (int) (Math.abs(seq) % 1000000) + 1;
            if (type == Types.NUMERIC || type == Types.DECIMAL || type == Types.FLOAT || type == Types.DOUBLE || type == Types.REAL) {
                return (double) seq;
            }

            // UUID
            if (typeName.contains("uuid")) {
                return UUID.randomUUID();
            }

            // String unique constraints
            int maxLen = col.size > 0 ? col.size : 50;
            if (maxLen <= 1) {
                return String.valueOf((char) ('A' + ((seq - 1) % 26)));
            }
            if (maxLen <= 2) {
                return generateTwoCharCode(seq);
            }
            if (maxLen <= 5) {
                return generateShortAlphaNum(seq, maxLen);
            }
            String base = (colName.contains("email"))
                    ? "user" + seq + "@bnp.com"
                    : col.name.toUpperCase() + "_" + seq;
            return base.length() > maxLen ? base.substring(0, maxLen) : base;
        }

        // 3. PostgreSQL & Specialized Data Types:
        // A. PostgreSQL tsvector
        if (typeName.contains("tsvector")) {
            return faker.lorem().sentence();
        }

        // B. Binary / Bytea
        if (typeName.contains("bytea") || type == Types.BINARY || type == Types.VARBINARY 
                || type == Types.LONGVARBINARY || type == Types.BLOB 
                || colName.contains("picture") || colName.contains("avatar") 
                || colName.contains("image") || colName.contains("photo") || colName.contains("blob")) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return bytes;
        }

        // C. PostgreSQL Arrays (e.g. special_features text[], tags)
        if (type == Types.ARRAY || typeName.startsWith("_") || typeName.endsWith("[]") 
                || colName.contains("special_features") || colName.contains("features") || colName.contains("tags")) {
            if (colName.contains("feature")) {
                String[] opts = {"Trailers", "Commentaries", "Deleted Scenes", "Behind the Scenes"};
                return new String[]{opts[random.nextInt(opts.length)]};
            }
            return new String[]{"item1", "item2"};
        }

        // D. PostgreSQL Enum - MPAA Rating
        if (colName.contains("rating") || typeName.contains("rating") || typeName.contains("mpaa")) {
            String[] ratings = {"G", "PG", "PG-13", "R", "NC-17"};
            return ratings[random.nextInt(ratings.length)];
        }

        // E. Year (e.g. film.release_year)
        if (colName.contains("year") || typeName.contains("year")) {
            return (int) (1990 + (seq % 35));
        }

        // F. UUID (only when typeName actually contains uuid)
        if (typeName.contains("uuid")) {
            return UUID.randomUUID();
        }

        // G. JSON / JSONB
        if (typeName.contains("json")) {
            return "{\"status\":\"ok\",\"seq\":" + seq + "}";
        }

        // 4. Inferred Foreign Key Columns (ends with _id or is id)
        if (colName.endsWith("_id") || colName.endsWith("id") || colName.equals("id")) {
            String assumedParent = colName.endsWith("_id") ? colName.substring(0, colName.length() - 3) : colName;
            for (Map.Entry<String, List<Object>> entry : generatedPrimaryKeys.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(assumedParent + ".") || key.equalsIgnoreCase(assumedParent)) {
                    List<Object> parentKeys = entry.getValue();
                    if (parentKeys != null && !parentKeys.isEmpty()) {
                        return parentKeys.get(random.nextInt(parentKeys.size()));
                    }
                }
            }

            if (type == Types.BIGINT) return seq;
            if (type == Types.SMALLINT) return (int) (seq % 1000) + 1;
            if (type == Types.TINYINT) return (int) (seq % 120) + 1;
            return (int) (seq % 50000) + 1;
        }

        // 5. Heuristic mapping based on column names with strict length clipping
        int maxLen = col.size > 0 ? col.size : 255;

        if (colName.contains("email") || colName.contains("mail")) {
            String email = "u" + seq + "_" + faker.internet().emailAddress();
            return clipString(email, maxLen);
        }
        if (colName.equals("first_name") || colName.equals("fname")) {
            return clipString(faker.name().firstName(), maxLen);
        }
        if (colName.equals("last_name") || colName.equals("lname") || colName.equals("surname")) {
            return clipString(faker.name().lastName(), maxLen);
        }
        if (colName.contains("name")) {
            return clipString(faker.name().fullName(), maxLen);
        }
        if (colName.contains("phone") || colName.contains("mobile") || colName.contains("tel")) {
            String phone = faker.phoneNumber().cellPhone().replaceAll("[^0-9+]", "");
            return clipString(phone.isEmpty() ? "9876543210" : phone, maxLen);
        }
        if (colName.contains("address") || colName.contains("street")) {
            return clipString(faker.address().streetAddress(), maxLen);
        }
        if (colName.contains("city")) {
            return clipString(faker.address().city(), maxLen);
        }
        if (colName.contains("zip") || colName.contains("postal")) {
            return clipString(faker.address().zipCode(), maxLen);
        }
        if (colName.contains("country")) {
            if (maxLen <= 2) return generateCountryCode(seq);
            if (maxLen <= 3) return "USA";
            return clipString(faker.address().country(), maxLen);
        }
        if (colName.contains("state") || colName.contains("province")) {
            if (maxLen <= 2) return generateStateCode(seq);
            return clipString(faker.address().state(), maxLen);
        }
        if (colName.contains("company") || colName.contains("dept") || colName.contains("department")) {
            return clipString(faker.company().name(), maxLen);
        }
        if (colName.contains("status")) {
            String[] statuses = {"ACTIVE", "PENDING", "COMPLETED", "APPROVED"};
            String status = statuses[random.nextInt(statuses.length)];
            return clipString(status, maxLen);
        }
        if (colName.contains("ssn") || colName.contains("tax")) {
            return clipString(faker.idNumber().valid(), maxLen);
        }
        if (colName.contains("card") || colName.contains("credit")) {
            return clipString(faker.finance().creditCard(), maxLen);
        }

        // 6. Numeric precision and scale constraint bounds checking
        if (colName.contains("amount") || colName.contains("salary") || colName.contains("price") || colName.contains("balance")
                || colName.contains("cost") || colName.contains("rate")
                || type == Types.NUMERIC || type == Types.DECIMAL || type == Types.FLOAT || type == Types.DOUBLE || type == Types.REAL) {
            int scale = Math.max(0, col.scale);
            int prec = col.precision > 0 ? col.precision : 10;
            int intDigits = Math.max(1, Math.min(prec - scale, 7));
            long maxIntVal = (long) Math.pow(10, intDigits) - 1;
            double num = 10.0 + (random.nextDouble() * (maxIntVal > 10 ? (maxIntVal - 10) : 9));
            double multiplier = Math.pow(10, scale);
            return Math.round(num * multiplier) / multiplier;
        }

        // 7. Date & Timestamp columns
        if (type == Types.DATE || colName.endsWith("_date") || colName.equals("date")) {
            return java.sql.Date.valueOf(LocalDate.now().minusDays(random.nextInt(730)));
        }
        if (type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE || type == Types.TIME
                || typeName.contains("timestamp") || typeName.contains("time")
                || colName.contains("time") || colName.contains("last_update") || colName.contains("created_at") || colName.contains("updated_at")) {
            return java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(random.nextInt(8760)));
        }

        // 8. Other data types
        switch (type) {
            case Types.TINYINT:
                return (int) (Math.abs(seq) % 120) + 1;
            case Types.SMALLINT:
                return (int) (Math.abs(seq) % 32000) + 1;
            case Types.INTEGER:
                return (int) (Math.abs(seq) % 1000000) + 1;
            case Types.BIGINT:
                return seq;
            case Types.BOOLEAN:
            case Types.BIT:
                return random.nextBoolean();
            case Types.CHAR:
                if (maxLen <= 1) return String.valueOf((char) ('A' + ((seq - 1) % 26)));
                if (maxLen <= 2) return generateTwoCharCode(seq);
                if (maxLen <= 5) return generateShortAlphaNum(seq, maxLen);
                return clipString(faker.lorem().word(), maxLen);
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            default:
                if (maxLen <= 1) return String.valueOf((char) ('A' + ((seq - 1) % 26)));
                if (maxLen <= 2) return generateTwoCharCode(seq);
                if (maxLen <= 5) return generateShortAlphaNum(seq, maxLen);
                String word = faker.lorem().word();
                return clipString(word, maxLen);
        }
    }

    private String clipString(String str, int maxLen) {
        if (str == null) return null;
        if (maxLen <= 0) maxLen = 255;
        return str.length() > maxLen ? str.substring(0, maxLen) : str;
    }

    private void setPreparedStatementValue(PreparedStatement pstmt, int index, ColumnDetail col, Object val) throws SQLException {
        if (val == null) {
            pstmt.setNull(index, col.dataType);
            return;
        }

        // 1. Binary / Bytea
        if (val instanceof byte[]) {
            pstmt.setBytes(index, (byte[]) val);
            return;
        }
        if (col.typeName != null && col.typeName.toLowerCase().contains("bytea")) {
            if (val instanceof byte[]) {
                pstmt.setBytes(index, (byte[]) val);
            } else {
                pstmt.setBytes(index, val.toString().getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        // 2. Arrays
        if (val instanceof String[] || col.dataType == Types.ARRAY
                || (col.typeName != null && (col.typeName.startsWith("_") || col.typeName.endsWith("[]")))) {
            String baseType = "text";
            if (col.typeName != null) {
                String tn = col.typeName.toLowerCase();
                if (tn.startsWith("_")) baseType = tn.substring(1);
                else if (tn.endsWith("[]")) baseType = tn.substring(0, tn.length() - 2);
            }
            Object[] arr = (val instanceof Object[]) ? (Object[]) val : new Object[]{val.toString()};
            try {
                Array sqlArray = pstmt.getConnection().createArrayOf(baseType, arr);
                pstmt.setArray(index, sqlArray);
            } catch (Exception e) {
                String joined = "{" + Arrays.stream(arr).map(Object::toString).collect(Collectors.joining(",")) + "}";
                pstmt.setString(index, joined);
            }
            return;
        }

        // 3. Dates & Timestamps
        if (val instanceof java.sql.Date) {
            pstmt.setDate(index, (java.sql.Date) val);
            return;
        }
        if (val instanceof java.sql.Timestamp) {
            pstmt.setTimestamp(index, (java.sql.Timestamp) val);
            return;
        }
        if (col.dataType == Types.DATE) {
            pstmt.setDate(index, java.sql.Date.valueOf(LocalDate.now()));
            return;
        }
        if (col.dataType == Types.TIMESTAMP || col.dataType == Types.TIMESTAMP_WITH_TIMEZONE) {
            pstmt.setTimestamp(index, java.sql.Timestamp.valueOf(LocalDateTime.now()));
            return;
        }

        // 4. UUID
        if (val instanceof UUID) {
            pstmt.setObject(index, val);
            return;
        }

        // 5. Numbers (Types.SMALLINT, TINYINT, INTEGER, BIGINT, NUMERIC/DOUBLE)
        if (col.dataType == Types.SMALLINT) {
            long lVal = 1L;
            if (val instanceof Number) {
                lVal = ((Number) val).longValue();
            } else {
                try { lVal = Long.parseLong(val.toString()); } catch (Exception ignored) {}
            }
            short sVal = (short) (lVal > 0 && lVal <= 32767 ? lVal : (Math.abs(lVal) % 32000 + 1));
            pstmt.setShort(index, sVal);
            return;
        }
        if (col.dataType == Types.TINYINT) {
            long lVal = 1L;
            if (val instanceof Number) {
                lVal = ((Number) val).longValue();
            } else {
                try { lVal = Long.parseLong(val.toString()); } catch (Exception ignored) {}
            }
            short sVal = (short) (lVal > 0 && lVal <= 127 ? lVal : (Math.abs(lVal) % 120 + 1));
            pstmt.setShort(index, sVal);
            return;
        }
        if (col.dataType == Types.INTEGER) {
            long lVal = 1L;
            if (val instanceof Number) {
                lVal = ((Number) val).longValue();
            } else {
                try { lVal = Long.parseLong(val.toString()); } catch (Exception ignored) {}
            }
            int iVal = (int) (lVal > 0 && lVal <= Integer.MAX_VALUE ? lVal : (Math.abs(lVal) % 1000000 + 1));
            pstmt.setInt(index, iVal);
            return;
        }
        if (col.dataType == Types.BIGINT) {
            long lVal = 1L;
            if (val instanceof Number) {
                lVal = ((Number) val).longValue();
            } else {
                try { lVal = Long.parseLong(val.toString()); } catch (Exception ignored) {}
            }
            pstmt.setLong(index, lVal);
            return;
        }
        if (val instanceof Double || col.dataType == Types.NUMERIC || col.dataType == Types.DECIMAL 
                || col.dataType == Types.FLOAT || col.dataType == Types.DOUBLE || col.dataType == Types.REAL) {
            double dVal = 0.0;
            if (val instanceof Number) {
                dVal = ((Number) val).doubleValue();
            } else {
                try { dVal = Double.parseDouble(val.toString()); } catch (Exception ignored) {}
            }
            pstmt.setDouble(index, dVal);
            return;
        }
        if (val instanceof Boolean) {
            pstmt.setBoolean(index, (Boolean) val);
            return;
        }

        // 6. Postgres custom types & Enums (Types.OTHER)
        if (col.typeName != null && col.typeName.toLowerCase().contains("tsvector")) {
            pstmt.setString(index, val != null ? val.toString() : "");
            return;
        }

        if (col.dataType == Types.OTHER && col.typeName != null) {
            String tn = col.typeName.toLowerCase();
            if (!tn.contains("varchar") && !tn.contains("text") && !tn.contains("tsvector")) {
                try {
                    org.postgresql.util.PGobject pgObj = new org.postgresql.util.PGobject();
                    pgObj.setType(col.typeName);
                    pgObj.setValue(val.toString());
                    pstmt.setObject(index, pgObj);
                    return;
                } catch (Exception ignored) {
                    // fall through to string binding
                }
            }
        }

        // 7. Strict datatype conversion if string or other object passed for numeric column
        String strVal = val.toString();
        if (col.dataType == Types.SMALLINT || col.dataType == Types.TINYINT) {
            try {
                pstmt.setShort(index, Short.parseShort(strVal));
            } catch (Exception ex) {
                pstmt.setShort(index, (short) 1);
            }
            return;
        } else if (col.dataType == Types.INTEGER) {
            try {
                pstmt.setInt(index, Integer.parseInt(strVal));
            } catch (Exception ex) {
                pstmt.setInt(index, 1);
            }
            return;
        } else if (col.dataType == Types.BIGINT) {
            try {
                pstmt.setLong(index, Long.parseLong(strVal));
            } catch (Exception ex) {
                pstmt.setLong(index, 1L);
            }
            return;
        } else if (col.dataType == Types.NUMERIC || col.dataType == Types.DECIMAL || col.dataType == Types.FLOAT || col.dataType == Types.DOUBLE || col.dataType == Types.REAL) {
            try {
                pstmt.setDouble(index, Double.parseDouble(strVal));
            } catch (Exception ex) {
                pstmt.setDouble(index, 0.0);
            }
            return;
        }
        if (col.size > 0 && strVal.length() > col.size) {
            strVal = strVal.substring(0, col.size);
        }
        pstmt.setString(index, strVal);
    }

    private Object generateMongoValueForField(String field, long seq) {
        return generateMongoValueForField(field, seq, null);
    }

    private Object generateMongoValueForField(String field, long seq, Integer maxLen) {
        String f = field.toLowerCase();
        int limit = (maxLen != null && maxLen > 0) ? maxLen : 0;

        if (limit == 1) {
            return String.valueOf((char) ('A' + ((seq - 1) % 26)));
        }
        if (limit == 2 || f.contains("country_code") || f.contains("state_code") || f.equals("st") || f.equals("cc")) {
            return generateTwoCharCode(seq);
        }
        if (limit > 0 && limit <= 5) {
            return generateShortAlphaNum(seq, limit);
        }

        Object result;
        if (f.contains("email")) result = faker.internet().emailAddress();
        else if (f.contains("name")) result = faker.name().fullName();
        else if (f.contains("phone")) result = faker.phoneNumber().cellPhone();
        else if (f.contains("address")) result = faker.address().streetAddress();
        else if (f.contains("city")) result = faker.address().city();
        else if (f.contains("country")) {
            result = (limit > 0 && limit <= 2) ? generateCountryCode(seq) : faker.address().country();
        } else if (f.contains("state") || f.contains("province")) {
            result = (limit > 0 && limit <= 2) ? generateStateCode(seq) : faker.address().state();
        } else if (f.contains("company")) result = faker.company().name();
        else if (f.contains("status")) {
            String[] s = {"ACTIVE", "COMPLETED", "INACTIVE", "PENDING"};
            result = s[(int) (Math.max(0, seq - 1) % s.length)];
        } else if (f.contains("amount") || f.contains("price") || f.contains("salary") || f.contains("balance")) {
            result = Math.round((100.0 + random.nextDouble() * 5000.0) * 100.0) / 100.0;
        } else if (f.contains("date") || f.contains("created") || f.contains("updated")) {
            result = new Date(System.currentTimeMillis() - (random.nextInt(365) * 86400000L));
        } else if (f.contains("age")) result = 20 + random.nextInt(50);
        else if (f.contains("count") || f.contains("num")) result = (int) (seq % 100);
        else if (f.contains("active") || f.contains("enabled")) return random.nextBoolean();
        else result = faker.lorem().word();

        if (limit > 0 && result instanceof String) {
            String str = (String) result;
            if (str.length() > limit) {
                return str.substring(0, limit);
            }
        }
        return result;
    }

    private Object generateJsonValueForField(String field, long seq) {
        String f = field.toLowerCase();
        if (f.equals("id") || f.endsWith("_id") || f.endsWith("id")) return seq;
        if (f.contains("email")) return faker.internet().emailAddress();
        if (f.contains("name")) return faker.name().fullName();
        if (f.contains("phone")) return faker.phoneNumber().cellPhone();
        if (f.contains("address")) return faker.address().streetAddress();
        if (f.contains("city")) return faker.address().city();
        if (f.contains("country")) return faker.address().country();
        if (f.contains("dept") || f.contains("department")) {
            String[] depts = {"FINANCE", "RISK", "ENGINEERING", "OPERATIONS", "COMPLIANCE"};
            return depts[random.nextInt(depts.length)];
        }
        if (f.contains("status")) {
            String[] s = {"ACTIVE", "APPROVED", "PENDING", "COMPLETED"};
            return s[random.nextInt(s.length)];
        }
        if (f.contains("amount") || f.contains("salary") || f.contains("price")) {
            return Math.round((5000.0 + random.nextDouble() * 15000.0) * 100.0) / 100.0;
        }
        if (f.contains("date") || f.contains("created")) {
            return LocalDate.now().minusDays(random.nextInt(365)).toString();
        }
        if (f.contains("active") || f.contains("enabled")) return random.nextBoolean();

        return faker.lorem().word();
    }
}
