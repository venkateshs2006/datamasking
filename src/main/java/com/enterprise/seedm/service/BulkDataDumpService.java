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

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Date;
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
            if (cos != null && "Local".equalsIgnoreCase(cos.getStorageType())) {
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
                String placeholders = insertColumns.stream()
                        .map(c -> "?")
                        .collect(Collectors.joining(", "));

                String insertSql = String.format("INSERT INTO \"%s\".\"%s\" (%s) VALUES (%s)",
                        targetSchema, tableName, colNamesJoined, placeholders);

                long inserted = 0;
                int batchSize = 100;
                List<Map<String, Object>> sampleRows = new ArrayList<>();

                try (PreparedStatement pstmt = jdbcConn.prepareStatement(insertSql)) {
                    while (inserted < targetCount) {
                        int currentBatch = (int) Math.min(batchSize, targetCount - inserted);

                        for (int i = 0; i < currentBatch; i++) {
                            long rowSeq = inserted + i + 1;
                            Map<String, Object> sampleRow = (sampleRows.size() < 5) ? new LinkedHashMap<>() : null;

                            for (int colIdx = 0; colIdx < insertColumns.size(); colIdx++) {
                                ColumnDetail col = insertColumns.get(colIdx);
                                Object val = generateValueForColumn(col, rowSeq, tableName, generatedPrimaryKeys);
                                setPreparedStatementValue(pstmt, colIdx + 1, col, val);

                                // If this column is a Primary Key, record it for child foreign keys
                                if (col.isPrimaryKey && val != null) {
                                    String pkLookupKey = tableName.toLowerCase() + "." + col.name.toLowerCase();
                                    generatedPrimaryKeys.computeIfAbsent(pkLookupKey, k -> new CopyOnWriteArrayList<>()).add(val);
                                }

                                if (sampleRow != null) {
                                    sampleRow.put(col.name, val != null ? val.toString() : null);
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
        for (ColumnDetail col : columns) {
            if (col.isPrimaryKey) {
                String key = tableName.toLowerCase() + "." + col.name.toLowerCase();
                List<Object> existing = generatedPrimaryKeys.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
                if (existing.isEmpty()) {
                    String query = String.format("SELECT \"%s\" FROM \"%s\".\"%s\" WHERE \"%s\" IS NOT NULL LIMIT 200",
                            col.name, schema, tableName, col.name);
                    try (Statement stmt = jdbcConn.createStatement();
                         ResultSet rs = stmt.executeQuery(query)) {
                        while (rs.next()) {
                            existing.add(rs.getObject(1));
                        }
                    } catch (Exception ignored) {}
                }
            }
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
                String fileName = tableConfig.getTableName();
                if (!fileName.toLowerCase().endsWith(".json")) {
                    fileName = fileName + ".json";
                }
                long targetCount = tableConfig.getTargetCount();

                progress.setCurrentTable(fileName);
                progress.setCurrentTableTarget(targetCount);
                progress.setCurrentTableInserted(0);
                progress.setCurrentTablePercent(0);
                progress.setMessage("Dumping JSON dataset (" + fileIndex + "/" + tables.size() + "): " + fileName + " [Target: " + targetCount + " records]");
                updateTableProgress(progress, fileName, "RUNNING", 0);

                List<String> fields = (tableConfig.getColumns() != null && !tableConfig.getColumns().isEmpty())
                        ? tableConfig.getColumns()
                        : List.of("id", "fullName", "email", "phone", "department", "status", "salary", "createdDate");

                ArrayNode rootArray = objectMapper.createArrayNode();
                List<Map<String, Object>> sampleRows = new ArrayList<>();

                long inserted = 0;
                long lastReported = 0;
                while (inserted < targetCount) {
                    long rowSeq = inserted + 1;
                    ObjectNode itemNode = rootArray.addObject();
                    Map<String, Object> sample = (sampleRows.size() < 5) ? new LinkedHashMap<>() : null;

                    for (String field : fields) {
                        Object val = generateJsonValueForField(field, rowSeq);
                        if (val instanceof Number) {
                            if (val instanceof Long) itemNode.put(field, (Long) val);
                            else if (val instanceof Integer) itemNode.put(field, (Integer) val);
                            else if (val instanceof Double) itemNode.put(field, (Double) val);
                        } else if (val instanceof Boolean) {
                            itemNode.put(field, (Boolean) val);
                        } else {
                            itemNode.put(field, val != null ? val.toString() : null);
                        }

                        if (sample != null) {
                            sample.put(field, val != null ? val.toString() : null);
                        }
                    }

                    if (sample != null) {
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
                        updateTableProgress(progress, fileName, "RUNNING", inserted);

                        int overall = calculateOverallPercent(progress);
                        progress.setOverallPercent(overall);

                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    }
                }

                // Output to COS or Local storage
                if (isCos && cosConn != null) {
                    File tempFile = File.createTempFile("bulk-dump-", "-" + fileName);
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, rootArray);
                    String objectKey = targetLocation.endsWith("/") ? targetLocation + fileName : targetLocation + "/" + fileName;
                    ibmCosService.uploadFile(cosConn, objectKey, tempFile.toPath());
                    tempFile.delete();
                    progress.getLogs().add("File `" + fileName + "`: successfully uploaded " + inserted + " records to COS bucket [" + cosConn.getBucketName() + "] key: " + objectKey);
                } else {
                    File outputFile = new File(targetLocation, fileName);
                    if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, rootArray);
                    progress.getLogs().add("File `" + fileName + "`: successfully dumped " + inserted + " JSON records to local file: " + outputFile.getAbsolutePath());
                }

                progress.getSampleDataReport().put(fileName, sampleRows);
                updateTableProgress(progress, fileName, "COMPLETED", inserted);
                progress.setProcessedTables(progress.getProcessedTables() + 1);
            }

            progress.setStatus("COMPLETED");
            progress.setEndTime(System.currentTimeMillis());
            progress.setOverallPercent(100);
            progress.setMessage("JSON bulk dump completed successfully!");
            progress.getLogs().add("Bulk dump finished. Total JSON records dumped: " + progress.getTotalInsertedRecords());

        } catch (Exception e) {
            log.error("JSON bulk dump error", e);
            throw new RuntimeException("JSON bulk dump failed: " + e.getMessage(), e);
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
                if (fkCol != null) {
                    fkCols.put(fkCol.toLowerCase(), new String[]{pkTable, pkCol});
                }
            }
        } catch (Exception ignored) {}

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
        // 1. Maintain Foreign Key Referential Integrity
        if (col.foreignTable != null && col.foreignColumn != null) {
            String fkLookupKey = col.foreignTable.toLowerCase() + "." + col.foreignColumn.toLowerCase();
            List<Object> parentKeys = generatedPrimaryKeys.get(fkLookupKey);
            if (parentKeys != null && !parentKeys.isEmpty()) {
                return parentKeys.get(random.nextInt(parentKeys.size()));
            }
            if (col.isNullable && random.nextBoolean()) {
                return null;
            }
            // Fallback for foreign key if parent table empty
            if (col.size > 0 && col.size <= 2) {
                return "R" + (seq % 10);
            }
            return (col.dataType == Types.BIGINT || col.dataType == Types.INTEGER || col.dataType == Types.SMALLINT)
                    ? 1L : (col.size > 0 && col.size < 5 ? "R" + (seq % 9 + 1) : "REF-1");
        }

        // 2. Primary Key / Unique constraints: guarantee uniqueness
        if (col.isPrimaryKey || col.isUnique) {
            if (col.dataType == Types.BIGINT) return seq;
            if (col.dataType == Types.INTEGER || col.dataType == Types.SMALLINT || col.dataType == Types.TINYINT) {
                return (int) (seq % 1000000);
            }
            if (col.typeName != null && col.typeName.toLowerCase().contains("uuid")) {
                return UUID.randomUUID();
            }
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
            String base = (col.name.toLowerCase().contains("email"))
                    ? "user" + seq + "@bnp.com"
                    : col.name.toUpperCase() + "_" + seq;
            return base.length() > maxLen ? base.substring(0, maxLen) : base;
        }

        String colName = col.name.toLowerCase();
        int type = col.dataType;
        String typeName = col.typeName != null ? col.typeName.toLowerCase() : "";

        // UUID Data Type Check
        if (typeName.contains("uuid") || type == Types.OTHER) {
            return UUID.randomUUID();
        }

        // 3. ID / Reference Columns & Foreign Key Inferences
        if (colName.endsWith("_id") || colName.endsWith("id") || colName.equals("id")) {
            // Check inferred foreign key if not explicitly marked by JDBC
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

        // Heuristic mapping based on column names with strict length clipping
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

        // Numeric precision and scale constraint bounds checking
        if (colName.contains("amount") || colName.contains("salary") || colName.contains("price") || colName.contains("balance")
                || type == Types.NUMERIC || type == Types.DECIMAL || type == Types.FLOAT || type == Types.DOUBLE || type == Types.REAL) {
            int scale = Math.max(0, col.scale);
            int prec = col.precision > 0 ? col.precision : 10;
            int intDigits = Math.max(1, Math.min(prec - scale, 7));
            long maxIntVal = (long) Math.pow(10, intDigits) - 1;
            double num = 10.0 + (random.nextDouble() * (maxIntVal > 10 ? (maxIntVal - 10) : 9));
            double multiplier = Math.pow(10, scale);
            return Math.round(num * multiplier) / multiplier;
        }

        // Integer data types
        switch (type) {
            case Types.TINYINT:
                return (int) (seq % 120);
            case Types.SMALLINT:
                return (int) (seq % 30000);
            case Types.INTEGER:
                return (int) (seq % 1000000);
            case Types.BIGINT:
                return seq;
            case Types.BOOLEAN:
            case Types.BIT:
                return random.nextBoolean();
            case Types.DATE:
                return java.sql.Date.valueOf(LocalDate.now().minusDays(random.nextInt(730)));
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(random.nextInt(8760)));
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

        if (val instanceof UUID) {
            pstmt.setObject(index, val);
        } else if (val instanceof java.sql.Date) {
            pstmt.setDate(index, (java.sql.Date) val);
        } else if (val instanceof java.sql.Timestamp) {
            pstmt.setTimestamp(index, (java.sql.Timestamp) val);
        } else if (val instanceof Long) {
            if (col.dataType == Types.SMALLINT || col.dataType == Types.TINYINT) {
                pstmt.setShort(index, ((Long) val).shortValue());
            } else if (col.dataType == Types.INTEGER) {
                pstmt.setInt(index, ((Long) val).intValue());
            } else {
                pstmt.setLong(index, (Long) val);
            }
        } else if (val instanceof Integer) {
            if (col.dataType == Types.SMALLINT || col.dataType == Types.TINYINT) {
                pstmt.setShort(index, ((Integer) val).shortValue());
            } else if (col.dataType == Types.BIGINT) {
                pstmt.setLong(index, ((Integer) val).longValue());
            } else {
                pstmt.setInt(index, (Integer) val);
            }
        } else if (val instanceof Short) {
            if (col.dataType == Types.INTEGER) {
                pstmt.setInt(index, ((Short) val).intValue());
            } else if (col.dataType == Types.BIGINT) {
                pstmt.setLong(index, ((Short) val).longValue());
            } else {
                pstmt.setShort(index, (Short) val);
            }
        } else if (val instanceof Double) {
            if (col.dataType == Types.INTEGER || col.dataType == Types.SMALLINT || col.dataType == Types.TINYINT) {
                pstmt.setInt(index, ((Double) val).intValue());
            } else if (col.dataType == Types.BIGINT) {
                pstmt.setLong(index, ((Double) val).longValue());
            } else {
                pstmt.setDouble(index, (Double) val);
            }
        } else if (val instanceof Boolean) {
            pstmt.setBoolean(index, (Boolean) val);
        } else {
            // Strict datatype conversion if string or other object passed for numeric column
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
        if (f.equals("id")) return seq;
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
