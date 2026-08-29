package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.model.SecureImportConfig;
import com.enterprise.seedm.model.SecureImportJob;
import com.enterprise.seedm.repository.SecureImportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@Slf4j
public class SecureImportService {

    @Autowired
    private SecureImportJobRepository jobRepository;

    @Autowired
    private DbConnectionService dbConnectionService;

    @Autowired
    private CosConnectionService cosConnectionService;

    private final ObjectMapper objectMapper;

    // In-memory store for real-time progress tracking
    private final Map<String, SecureImportProgress> progressMap = new ConcurrentHashMap<>();

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile("^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:([a-zA-Z0-9_]+)\\.)?([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_INTO_PATTERN = Pattern.compile("^\\s*INSERT\\s+INTO\\s+(?:([a-zA-Z0-9_]+)\\.)?([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE);

    public SecureImportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve the source file path based on storage configuration.
     */
    public Path resolveSourceFile(SecureImportConfig config) throws FileNotFoundException {
        if (config == null || config.getStorage() == null) {
            throw new IllegalArgumentException("Storage configuration is missing");
        }

        SecureImportConfig.StorageConfig storage = config.getStorage();
        String storageType = storage.getType() != null ? storage.getType().toLowerCase() : "local";
        Path candidatePath = null;

        if ("cos".equals(storageType)) {
            if (storage.getId() != null) {
                CosConnection cos = cosConnectionService.getConnection(storage.getId());
                if (cos != null && cos.getStorageLocation() != null && !cos.getStorageLocation().trim().isEmpty()) {
                    candidatePath = Paths.get(cos.getStorageLocation());
                }
            }
            if (candidatePath == null && storage.getPath() != null && !storage.getPath().trim().isEmpty()) {
                candidatePath = Paths.get(storage.getPath());
            }
            if (candidatePath == null) {
                candidatePath = Paths.get("secure-export");
            }
        } else {
            // Local storage
            String localPathStr = storage.getPath();
            if (localPathStr == null || localPathStr.trim().isEmpty()) {
                localPathStr = storage.getName();
            }
            if (localPathStr == null || localPathStr.trim().isEmpty()) {
                localPathStr = "secure-export";
            }
            candidatePath = Paths.get(localPathStr);
        }

        if (Files.isRegularFile(candidatePath)) {
            return candidatePath;
        }

        if (Files.isDirectory(candidatePath)) {
            // Check for specific file name if specified
            if (storage.getFileName() != null && !storage.getFileName().trim().isEmpty()) {
                Path specificFile = candidatePath.resolve(storage.getFileName().trim());
                if (Files.isRegularFile(specificFile)) {
                    return specificFile;
                }
            }

            // Check standard files
            Path encFile = candidatePath.resolve("secure-export.sql.enc");
            if (Files.isRegularFile(encFile)) {
                return encFile;
            }
            Path sqlFile = candidatePath.resolve("secure-export.sql");
            if (Files.isRegularFile(sqlFile)) {
                return sqlFile;
            }

            // Scan directory for any .sql.enc or .sql file
            try (Stream<Path> stream = Files.walk(candidatePath, 1)) {
                Optional<Path> found = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".sql.enc") || p.toString().endsWith(".sql"))
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (IOException e) {
                log.warn("Error walking directory: {}", candidatePath, e);
            }
        }

        throw new FileNotFoundException("Could not locate export file in path: " + candidatePath.toAbsolutePath());
    }

    /**
     * Validate secret key by attempting to decrypt the header of the encrypted file.
     */
    public void validateSecretKey(SecureImportConfig config, String secretKey) throws Exception {
        Path sourceFile = resolveSourceFile(config);

        if (!sourceFile.toString().endsWith(".enc")) {
            // Plain SQL file does not require decryption key
            return;
        }

        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret key is required to decrypt the secure export file (.sql.enc)");
        }

        try (InputStream in = Files.newInputStream(sourceFile)) {
            byte[] iv = new byte[16];
            int read = in.read(iv);
            if (read < 16) {
                throw new IllegalArgumentException("Invalid encrypted file: file size too small for initialization vector");
            }

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(secretKey.trim().getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec);

            try (CipherInputStream cipherIn = new CipherInputStream(in, cipher);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(cipherIn, StandardCharsets.UTF_8))) {
                
                // Read first 5 lines to verify decryption validity
                StringBuilder headerSample = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    String line = reader.readLine();
                    if (line == null) break;
                    headerSample.append(line).append("\n");
                }

                String sampleStr = headerSample.toString();
                if (sampleStr.isEmpty() || !isValidSqlContent(sampleStr)) {
                    throw new IllegalArgumentException("Invalid secret key. Decrypted content did not match expected SQL structure.");
                }
            }
        } catch (Exception e) {
            log.warn("Secret key validation failed for file {}: {}", sourceFile, e.getMessage());
            throw new IllegalArgumentException("Invalid secret key. Decryption failed: " + e.getMessage(), e);
        }
    }

    private boolean isValidSqlContent(String text) {
        String trimmed = text.trim();
        return trimmed.startsWith("--") ||
                trimmed.toUpperCase().startsWith("CREATE") ||
                trimmed.toUpperCase().startsWith("INSERT") ||
                trimmed.toUpperCase().startsWith("SET") ||
                trimmed.toUpperCase().startsWith("ALTER") ||
                trimmed.toUpperCase().startsWith("DROP");
    }

    /**
     * Process secure import migration asynchronously.
     */
    public String processSecureImport(String executionId, SecureImportConfig config, String secretKey) {
        log.info("Starting secure import execution: {}", executionId);

        SecureImportProgress progress = progressMap.computeIfAbsent(executionId, k -> new SecureImportProgress());
        progress.executionId = executionId;
        progress.setStatus("RUNNING");
        progress.setStartTime(System.currentTimeMillis());

        saveJob(executionId, config, "RUNNING", null);

        int processedTables = 0;
        Set<String> discoveredTables = new LinkedHashSet<>();
        Map<String, AtomicInteger> tableRecordCounts = new HashMap<>();

        try {
            Path sourceFile = resolveSourceFile(config);
            log.info("Importing from source file: {}", sourceFile.toAbsolutePath());

            // First pass or stream processing: Read statements
            // Resolve target connection details
            String destUrl = null;
            String destUser = null;
            String destPass = null;
            String destSchema = "public";

            if (config.getDest() != null) {
                if (config.getDest().getId() != null) {
                    DbConnection dbConn = dbConnectionService.getConnection(config.getDest().getId());
                    if (dbConn != null) {
                        destUrl = dbConn.getUrl();
                        destUser = dbConn.getUsername();
                        destPass = dbConn.getPassword();
                    }
                }
                if (destUrl == null && config.getDest().getUrl() != null) {
                    destUrl = config.getDest().getUrl();
                    destUser = config.getDest().getUsername();
                    destPass = config.getDest().getPassword();
                }
                if (config.getDest().getSchema() != null && !config.getDest().getSchema().trim().isEmpty()) {
                    destSchema = config.getDest().getSchema().trim();
                }
            }

            if (destUrl == null) {
                throw new IllegalArgumentException("Destination database connection details not found");
            }

            // Execute migration against destination database
            try (Connection connection = DriverManager.getConnection(destUrl, destUser, destPass)) {
                connection.setAutoCommit(false);

                // Set search_path for postgres
                try (Statement stmt = connection.createStatement()) {
                    if (destSchema != null && !destSchema.trim().isEmpty()) {
                        stmt.execute("CREATE SCHEMA IF NOT EXISTS " + destSchema.trim());
                        stmt.execute("SET search_path TO " + destSchema.trim() + ", public");
                    }
                    connection.commit();
                } catch (Exception ex) {
                    log.warn("Could not set search_path to {}: {}", destSchema, ex.getMessage());
                }

                // Prepare input stream (encrypted vs plain)
                boolean isEncrypted = sourceFile.toString().endsWith(".enc");
                try (InputStream rawIn = Files.newInputStream(sourceFile)) {
                    InputStream inStream = rawIn;

                    if (isEncrypted) {
                        byte[] iv = new byte[16];
                        int read = rawIn.read(iv);
                        if (read < 16) {
                            throw new IllegalArgumentException("Encrypted file too short for IV");
                        }
                        MessageDigest sha = MessageDigest.getInstance("SHA-256");
                        byte[] keyBytes = sha.digest(secretKey.trim().getBytes(StandardCharsets.UTF_8));
                        SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");
                        IvParameterSpec ivSpec = new IvParameterSpec(iv);

                        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec);
                        inStream = new CipherInputStream(rawIn, cipher);
                    }

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8), 128 * 1024)) {
                        String line;
                        StringBuilder currentStatement = new StringBuilder();
                        String currentTable = null;
                        List<String> insertBatch = new ArrayList<>(1000);

                        while ((line = reader.readLine()) != null) {
                            String trimmed = line.trim();

                            if (trimmed.startsWith("-- CREATE TABLE ")) {
                                flushBatch(connection, insertBatch);
                                String tblName = trimmed.substring("-- CREATE TABLE ".length()).trim();
                                if (!tblName.isEmpty()) {
                                    if (currentTable != null && !currentTable.equals(tblName)) {
                                        markTableComplete(progress, currentTable);
                                        processedTables++;
                                        progress.processedTables.set(processedTables);
                                        updateProgress(executionId, "RUNNING", processedTables, progress.totalTables.get(), null);
                                    }
                                    currentTable = tblName;
                                    discoveredTables.add(currentTable);
                                    registerTableIfAbsent(progress, currentTable);
                                }
                                continue;
                            }

                            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                                continue;
                            }

                            currentStatement.append(line).append("\n");

                            if (trimmed.endsWith(";")) {
                                String sql = currentStatement.toString().trim();
                                currentStatement.setLength(0);

                                if (!sql.isEmpty()) {
                                    Matcher createMatcher = CREATE_TABLE_PATTERN.matcher(sql);
                                    if (createMatcher.find()) {
                                        flushBatch(connection, insertBatch);
                                        String extractedTbl = createMatcher.group(2);
                                        if (extractedTbl != null && !extractedTbl.isEmpty()) {
                                            currentTable = extractedTbl;
                                            discoveredTables.add(currentTable);
                                            registerTableIfAbsent(progress, currentTable);
                                        }
                                        executeSingleStatement(connection, sql);
                                        continue;
                                    }

                                    Matcher insertMatcher = INSERT_INTO_PATTERN.matcher(sql);
                                    if (insertMatcher.find()) {
                                        String extractedTbl = insertMatcher.group(2);
                                        if (extractedTbl != null && !extractedTbl.isEmpty()) {
                                            currentTable = extractedTbl;
                                            registerTableIfAbsent(progress, currentTable);
                                            incrementTableRecord(progress, currentTable);
                                        }
                                        insertBatch.add(sql);
                                        if (insertBatch.size() >= 1000) {
                                            flushBatch(connection, insertBatch);
                                        }
                                    } else {
                                        // Other DDL / views / constraints / sequences
                                        flushBatch(connection, insertBatch);
                                        executeSingleStatement(connection, sql);
                                    }
                                }
                            }
                        }

                        flushBatch(connection, insertBatch);

                        if (currentTable != null) {
                            markTableComplete(progress, currentTable);
                            processedTables++;
                            progress.processedTables.set(processedTables);
                        }
                    }
                }
            }

            int total = progress.totalTables.get();
            if (total == 0) {
                total = discoveredTables.size();
                progress.totalTables.set(total);
                progress.processedTables.set(total);
            }

            updateProgress(executionId, "COMPLETED", progress.processedTables.get(), total, null);
            log.info("Secure import completed successfully for execution ID: {}", executionId);

        } catch (Exception e) {
            log.error("Secure import failed for execution ID: {}", executionId, e);
            updateProgress(executionId, "FAILED", processedTables, progress.totalTables.get(), e.getMessage());
        }

        return executionId;
    }

    private void flushBatch(Connection connection, List<String> batch) {
        if (batch == null || batch.isEmpty()) return;
        try (Statement stmt = connection.createStatement()) {
            for (String sql : batch) {
                stmt.addBatch(sql);
            }
            stmt.executeBatch();
            connection.commit();
        } catch (Exception e) {
            log.warn("Batch execution failed, falling back to sequential execution: {}", e.getMessage());
            try {
                connection.rollback();
            } catch (Exception ignored) {}
            for (String sql : batch) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute(sql);
                } catch (Exception sqlEx) {
                    log.warn("SQL warning on fallback execution: {} - Error: {}", sql.length() > 80 ? sql.substring(0, 80) + "..." : sql, sqlEx.getMessage());
                }
            }
            try {
                connection.commit();
            } catch (Exception ignored) {}
        } finally {
            batch.clear();
        }
    }

    private void executeSingleStatement(Connection connection, String sql) {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            connection.commit();
        } catch (Exception sqlEx) {
            log.warn("SQL execution warning on statement: {} - Error: {}", sql.length() > 80 ? sql.substring(0, 80) + "..." : sql, sqlEx.getMessage());
        }
    }

    private void registerTableIfAbsent(SecureImportProgress progress, String tableName) {
        synchronized (progress.tableProgress) {
            boolean exists = false;
            for (Map<String, Object> tp : progress.tableProgress) {
                if (tableName.equals(tp.get("tableName"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                Map<String, Object> entry = new ConcurrentHashMap<>();
                entry.put("tableName", tableName);
                entry.put("readCount", 0);
                entry.put("writeCount", 0);
                entry.put("status", "RUNNING");
                progress.tableProgress.add(entry);
                progress.totalTables.incrementAndGet();
            }
        }
    }

    private void incrementTableRecord(SecureImportProgress progress, String tableName) {
        progress.totalRecords.incrementAndGet();
        synchronized (progress.tableProgress) {
            for (Map<String, Object> tp : progress.tableProgress) {
                if (tableName.equals(tp.get("tableName"))) {
                    int r = (int) tp.getOrDefault("readCount", 0) + 1;
                    tp.put("readCount", r);
                    tp.put("writeCount", r);
                    tp.put("status", "RUNNING");
                    break;
                }
            }
        }
    }

    private void markTableComplete(SecureImportProgress progress, String tableName) {
        if (!progress.completedTables.contains(tableName)) {
            progress.completedTables.add(tableName);
        }
        synchronized (progress.tableProgress) {
            for (Map<String, Object> tp : progress.tableProgress) {
                if (tableName.equals(tp.get("tableName"))) {
                    tp.put("status", "COMPLETED");
                    break;
                }
            }
        }
    }

    public Map<String, Object> scanStorage(Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            Object idObj = request.get("cosId");
            if (idObj == null) idObj = request.get("id");
            String pathStr = (String) request.get("path");
            String type = (String) request.getOrDefault("type", "local");

            Long cosId = null;
            if (idObj != null) {
                try {
                    cosId = Long.parseLong(idObj.toString());
                } catch (Exception ignored) {}
            }

            Path dirPath = null;
            if ("cos".equalsIgnoreCase(type) && cosId != null) {
                CosConnection cos = cosConnectionService.getConnection(cosId);
                if (cos != null && cos.getStorageLocation() != null) {
                    dirPath = Paths.get(cos.getStorageLocation());
                }
            } else if (pathStr != null && !pathStr.trim().isEmpty()) {
                dirPath = Paths.get(pathStr.trim());
            }

            if (dirPath == null) {
                dirPath = Paths.get("secure-export");
            }

            List<Map<String, Object>> filesList = new ArrayList<>();
            if (Files.exists(dirPath)) {
                if (Files.isRegularFile(dirPath)) {
                    Map<String, Object> fInfo = new HashMap<>();
                    fInfo.put("name", dirPath.getFileName().toString());
                    fInfo.put("sizeBytes", Files.size(dirPath));
                    fInfo.put("encrypted", dirPath.toString().endsWith(".enc"));
                    filesList.add(fInfo);
                } else if (Files.isDirectory(dirPath)) {
                    try (Stream<Path> stream = Files.list(dirPath)) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".sql") || p.toString().endsWith(".sql.enc"))
                                .forEach(p -> {
                                    Map<String, Object> fInfo = new HashMap<>();
                                    fInfo.put("name", p.getFileName().toString());
                                    try {
                                        fInfo.put("sizeBytes", Files.size(p));
                                    } catch (IOException e) {
                                        fInfo.put("sizeBytes", 0);
                                    }
                                    fInfo.put("encrypted", p.toString().endsWith(".enc"));
                                    filesList.add(fInfo);
                                });
                    }
                }
            }

            response.put("status", "SUCCESS");
            response.put("path", dirPath.toAbsolutePath().toString());
            response.put("files", filesList);
            response.put("fileCount", filesList.size());
            return response;
        } catch (Exception e) {
            log.error("Failed to scan storage for import", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    public Map<String, Object> getProgress(String executionId) {
        SecureImportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            return progress.toMap();
        }

        SecureImportJob job = jobRepository.findByExecutionId(executionId);
        if (job == null) {
            return Map.of("status", "NOT_FOUND");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("executionId", job.getExecutionId());
        map.put("status", job.getStatus());
        map.put("errorMessage", job.getErrorMessage());
        map.put("startTime", job.getCreatedAt());
        map.put("endTime", job.getCompletedAt());
        return map;
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> executions = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // From memory
        for (Map.Entry<String, SecureImportProgress> entry : progressMap.entrySet()) {
            Map<String, Object> execMap = new HashMap<>();
            execMap.put("id", entry.getKey());
            execMap.put("status", entry.getValue().getStatus());
            execMap.put("startTime", entry.getValue().getStartTime());
            executions.add(execMap);
            seenIds.add(entry.getKey());
        }

        // From DB
        for (SecureImportJob job : jobRepository.findAll()) {
            if (!seenIds.contains(job.getExecutionId())) {
                Map<String, Object> execMap = new HashMap<>();
                execMap.put("id", job.getExecutionId());
                execMap.put("status", job.getStatus());
                execMap.put("startTime", job.getCreatedAt());
                executions.add(execMap);
                seenIds.add(job.getExecutionId());
            }
        }
        return executions;
    }

    private void saveJob(String executionId, SecureImportConfig config, String status, String errorMessage) {
        SecureImportJob job = new SecureImportJob();
        job.setExecutionId(executionId);
        job.setJobName(config.getJobName() != null ? config.getJobName() : "Secure Import Job");
        job.setStatus(status);
        job.setErrorMessage(errorMessage);
        job.setCreatedAt(System.currentTimeMillis());
        try {
            job.setConfigDetails(objectMapper.writeValueAsString(config));
        } catch (IOException e) {
            log.error("Failed to serialize secure import config details", e);
        }
        jobRepository.save(job);
    }

    private void updateProgress(String executionId, String status, int processed, int total, String errorMessage) {
        SecureImportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            progress.setStatus(status);
            progress.processedTables.set(processed);
            progress.totalTables.set(total);
            progress.setErrorMessage(errorMessage);
            if ("FAILED".equals(status) || "COMPLETED".equals(status)) {
                progress.setEndTime(System.currentTimeMillis());
            }
        }

        SecureImportJob job = jobRepository.findByExecutionId(executionId);
        if (job != null) {
            job.setStatus(status);
            job.setErrorMessage(errorMessage);
            if ("FAILED".equals(status) || "COMPLETED".equals(status)) {
                job.setCompletedAt(System.currentTimeMillis());
            }
            jobRepository.save(job);
        }
    }

    // Inner class for progress tracking
    private static class SecureImportProgress {
        private String executionId;
        private String status = "PENDING";
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
