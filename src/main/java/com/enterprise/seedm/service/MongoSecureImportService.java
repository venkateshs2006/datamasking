package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.model.MongoSecureImportConfig;
import com.enterprise.seedm.model.MongoSecureImportJob;
import com.enterprise.seedm.repository.MongoSecureImportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonBinaryReader;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MongoSecureImportService {

    private final DbConnectionService dbConnectionService;
    private final CosConnectionService cosConnectionService;
    private final IbmCosService ibmCosService;
    private final MongoSecureImportJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final MongoConnectionHelper mongoConnectionHelper;

    private static final String DEFAULT_IMPORT_DIR = "secure-export";
    private static final byte[] MAGIC_HEADER = "MONGOBSON1\n".getBytes(StandardCharsets.UTF_8);
    private static final byte MARKER_COLLECTION_START = 0x01;
    private static final byte MARKER_DOC_RECORD = 0x02;
    private static final byte MARKER_COLLECTION_END = 0x03;
    private static final byte MARKER_EOF = (byte) 0xFF;

    private final DocumentCodec documentCodec = new DocumentCodec();

    public static class MongoSecureImportProgress {
        public final AtomicInteger totalCollections = new AtomicInteger(0);
        public final AtomicInteger processedCollections = new AtomicInteger(0);
        public final AtomicLong totalRecords = new AtomicLong(0);
        public final List<Map<String, Object>> collectionProgress = Collections.synchronizedList(new ArrayList<>());
        public final List<String> completedCollections = Collections.synchronizedList(new ArrayList<>());
        public volatile String status = "PENDING";
        public volatile String errorMessage = null;
        public volatile long startTime = System.currentTimeMillis();
        public volatile long completedTime = 0;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("status", status);
            map.put("totalCollections", totalCollections.get());
            map.put("processedCollections", processedCollections.get());
            map.put("totalTables", totalCollections.get()); // Compatible with dashboard
            map.put("processedTables", processedCollections.get());
            map.put("totalRecords", totalRecords.get());
            map.put("tableProgress", new ArrayList<>(collectionProgress));
            map.put("completedTables", new ArrayList<>(completedCollections));
            map.put("errorMessage", errorMessage);
            map.put("startTime", startTime);
            map.put("completedTime", completedTime);
            long elapsedSeconds = (completedTime > 0 ? (completedTime - startTime) : (System.currentTimeMillis() - startTime)) / 1000;
            map.put("elapsedSeconds", elapsedSeconds);
            return map;
        }
    }

    private final Map<String, MongoSecureImportProgress> progressMap = new ConcurrentHashMap<>();

    public Map<String, Object> scanStorage(MongoSecureImportConfig.StorageConfig storage) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (storage != null && "cos".equalsIgnoreCase(storage.getType())) {
                Long cosId = storage.getId() != null ? storage.getId() : storage.getCosId();
                if (cosId != null && cosConnectionService != null && ibmCosService != null) {
                    CosConnection cos = cosConnectionService.getConnection(cosId);
                    if (cos != null && !"Local".equalsIgnoreCase(cos.getStorageType())) {
                        List<Map<String, Object>> cosObjects = ibmCosService.listObjects(cos, null);
                        List<Map<String, Object>> filesList = cosObjects.stream()
                                .filter(o -> {
                                    String name = (String) o.get("name");
                                    return name != null && name.toLowerCase().endsWith(".bson.enc");
                                })
                                .toList();

                        response.put("status", "SUCCESS");
                        response.put("path", "cos://" + ibmCosService.getEffectiveBucketName(cos));
                        response.put("files", filesList);
                        response.put("fileCount", filesList.size());
                        return response;
                    }
                }
            }

            Path dirPath = resolveStoragePath(storage);
            List<Map<String, Object>> filesList = new ArrayList<>();

            if (Files.exists(dirPath)) {
                if (Files.isRegularFile(dirPath)) {
                    String fname = dirPath.getFileName().toString();
                    if (fname.toLowerCase().endsWith(".bson.enc")) {
                        Map<String, Object> fInfo = new HashMap<>();
                        fInfo.put("name", fname);
                        fInfo.put("sizeBytes", Files.size(dirPath));
                        fInfo.put("encrypted", true);
                        filesList.add(fInfo);
                    }
                } else if (Files.isDirectory(dirPath)) {
                    try (Stream<Path> stream = Files.list(dirPath)) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().toLowerCase().endsWith(".bson.enc"))
                                .forEach(p -> {
                                    Map<String, Object> fInfo = new HashMap<>();
                                    fInfo.put("name", p.getFileName().toString());
                                    try {
                                        fInfo.put("sizeBytes", Files.size(p));
                                    } catch (IOException e) {
                                        fInfo.put("sizeBytes", 0);
                                    }
                                    fInfo.put("encrypted", true);
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
            log.error("Failed to scan storage for Mongo import", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    public void validateSecretKey(MongoSecureImportConfig config, String secretKey) {
        List<Path> sourceFiles = resolveSourceFiles(config);
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No MongoDB backup files found to validate secret key");
        }

        Path encFile = sourceFiles.stream()
                .filter(p -> p.getFileName().toString().endsWith(".enc"))
                .findFirst()
                .orElse(null);

        if (encFile == null) {
            return; // Plain file
        }

        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret key is required for encrypted file");
        }

        try (InputStream in = com.enterprise.seedm.util.BackupFileUtils.createDecryptedInputStream(encFile, secretKey)) {
            byte[] header = new byte[MAGIC_HEADER.length];
            int read = in.read(header);
            if (read < MAGIC_HEADER.length || !Arrays.equals(header, MAGIC_HEADER)) {
                throw new IllegalArgumentException("Invalid secret key. Header verification failed.");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Secret key validation failed for file {}: {}", encFile, e.getMessage());
            throw new IllegalArgumentException("Invalid secret key. Decryption verification failed: " + e.getMessage());
        }
    }

    public String processMongoImport(String executionId, MongoSecureImportConfig config, String secretKey) {
        MongoSecureImportProgress progress = progressMap.computeIfAbsent(executionId, k -> new MongoSecureImportProgress());
        progress.status = "RUNNING";
        progress.startTime = System.currentTimeMillis();

        int processedCollections = 0;

        try {
            saveJobRecord(executionId, config, "RUNNING", null);

            List<Path> sourceFiles = resolveSourceFiles(config);
            if (sourceFiles.isEmpty()) {
                throw new IllegalArgumentException("MongoDB import files not found");
            }

            String dbName = resolveMongoDatabase(config);
            if (dbName == null) {
                throw new IllegalArgumentException("Target MongoDB database name is missing");
            }

            log.info("Starting Mongo import for execution ID: {} with {} backup file(s): {}", executionId, sourceFiles.size(),
                    sourceFiles.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList()));

            try (MongoClient mongoClient = createClient(config)) {
                MongoDatabase database = mongoClient.getDatabase(dbName);
                Set<String> droppedCollections = new HashSet<>();
                Set<String> allEncounteredCollections = new LinkedHashSet<>();

                for (int fIdx = 0; fIdx < sourceFiles.size(); fIdx++) {
                    Path sourceFile = sourceFiles.get(fIdx);
                    log.info("Processing Mongo backup file ({}/{}): {}", fIdx + 1, sourceFiles.size(), sourceFile.getFileName());

                    try (InputStream rawIn = com.enterprise.seedm.util.BackupFileUtils.createDecryptedInputStream(sourceFile, secretKey);
                         DataInputStream dataIn = new DataInputStream(new BufferedInputStream(rawIn, 128 * 1024))) {

                        // Check magic header
                        byte[] header = new byte[MAGIC_HEADER.length];
                        dataIn.readFully(header);
                        if (!Arrays.equals(header, MAGIC_HEADER)) {
                            throw new IllegalArgumentException("Invalid file format: MAGIC header mismatch in file: " + sourceFile.getFileName());
                        }

                        boolean reading = true;
                        String currentCollection = null;
                        List<Document> batch = new ArrayList<>(1000);

                        while (reading) {
                            int marker = dataIn.read();
                            if (marker == -1 || marker == (MARKER_EOF & 0xFF)) {
                                reading = false;
                                break;
                            }

                            if (marker == MARKER_COLLECTION_START) {
                                int nameLen = dataIn.readUnsignedShort();
                                byte[] nameBytes = new byte[nameLen];
                                dataIn.readFully(nameBytes);
                                currentCollection = new String(nameBytes, StandardCharsets.UTF_8);

                                allEncounteredCollections.add(currentCollection);
                                registerCollectionIfAbsent(progress, currentCollection);

                                if (config.getDest() != null && config.getDest().isDropExisting() && !droppedCollections.contains(currentCollection)) {
                                    try {
                                        database.getCollection(currentCollection).drop();
                                        log.info("Dropped existing target collection: {}", currentCollection);
                                    } catch (Exception ex) {
                                        log.warn("Could not drop collection {}: {}", currentCollection, ex.getMessage());
                                    }
                                    droppedCollections.add(currentCollection);
                                }
                            } else if (marker == MARKER_DOC_RECORD) {
                                int docLen = dataIn.readInt();
                                byte[] bsonBytes = new byte[docLen];
                                dataIn.readFully(bsonBytes);

                                Document doc = decodeBsonToDocument(bsonBytes);
                                if (doc != null) {
                                    batch.add(doc);
                                    progress.totalRecords.incrementAndGet();
                                    incrementCollectionRecord(progress, currentCollection);

                                    if (batch.size() >= 1000 && currentCollection != null) {
                                        flushBatch(database.getCollection(currentCollection), batch);
                                    }
                                }
                            } else if (marker == MARKER_COLLECTION_END) {
                                if (currentCollection != null) {
                                    if (!batch.isEmpty()) {
                                        flushBatch(database.getCollection(currentCollection), batch);
                                    }
                                    currentCollection = null;
                                }
                            }
                        }

                        if (currentCollection != null && !batch.isEmpty()) {
                            flushBatch(database.getCollection(currentCollection), batch);
                        }
                    }
                }

                for (String coll : allEncounteredCollections) {
                    markCollectionComplete(progress, coll);
                    processedCollections++;
                    progress.processedCollections.set(processedCollections);
                }

                progress.status = "COMPLETED";
                progress.completedTime = System.currentTimeMillis();
                saveJobRecord(executionId, config, "COMPLETED", null);
                log.info("Mongo secure import completed for execution ID: {} from {} file(s)", executionId, sourceFiles.size());
            }
        } catch (Exception e) {
            log.error("Mongo secure import failed for execution ID: {}", executionId, e);
            progress.status = "FAILED";
            progress.errorMessage = e.getMessage();
            progress.completedTime = System.currentTimeMillis();
            saveJobRecord(executionId, config, "FAILED", e.getMessage());
        }

        return executionId;
    }

    private void flushBatch(MongoCollection<Document> collection, List<Document> batch) {
        if (batch == null || batch.isEmpty()) return;
        try {
            collection.insertMany(batch, new InsertManyOptions().ordered(false));
        } catch (Exception e) {
            log.warn("Batch insert issue on collection {}: {}", collection.getNamespace().getCollectionName(), e.getMessage());
        } finally {
            batch.clear();
        }
    }

    private Document decodeBsonToDocument(byte[] bsonBytes) {
        try {
            BsonBinaryReader reader = new BsonBinaryReader(ByteBuffer.wrap(bsonBytes));
            return documentCodec.decode(reader, DecoderContext.builder().build());
        } catch (Exception e) {
            log.error("Failed to decode BSON document", e);
            return null;
        }
    }

    private void registerCollectionIfAbsent(MongoSecureImportProgress progress, String collectionName) {
        synchronized (progress.collectionProgress) {
            boolean exists = false;
            for (Map<String, Object> cp : progress.collectionProgress) {
                if (collectionName.equals(cp.get("tableName"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                Map<String, Object> entry = new ConcurrentHashMap<>();
                entry.put("tableName", collectionName);
                entry.put("readCount", 0);
                entry.put("writeCount", 0);
                entry.put("status", "RUNNING");
                progress.collectionProgress.add(entry);
                progress.totalCollections.incrementAndGet();
            }
        }
    }

    private void incrementCollectionRecord(MongoSecureImportProgress progress, String collectionName) {
        if (collectionName == null) return;
        synchronized (progress.collectionProgress) {
            for (Map<String, Object> cp : progress.collectionProgress) {
                if (collectionName.equals(cp.get("tableName"))) {
                    int r = (int) cp.getOrDefault("writeCount", 0) + 1;
                    cp.put("readCount", r);
                    cp.put("writeCount", r);
                    cp.put("status", "RUNNING");
                    break;
                }
            }
        }
    }

    private void markCollectionComplete(MongoSecureImportProgress progress, String collectionName) {
        if (collectionName == null) return;
        if (!progress.completedCollections.contains(collectionName)) {
            progress.completedCollections.add(collectionName);
        }
        synchronized (progress.collectionProgress) {
            for (Map<String, Object> cp : progress.collectionProgress) {
                if (collectionName.equals(cp.get("tableName"))) {
                    cp.put("status", "COMPLETED");
                    break;
                }
            }
        }
    }

    private Path resolveStoragePath(MongoSecureImportConfig.StorageConfig storage) {
        if (storage != null) {
            Long sId = storage.getId() != null ? storage.getId() : storage.getCosId();
            if (sId != null && cosConnectionService != null) {
                CosConnection cos = cosConnectionService.getConnection(sId);
                if (cos != null && cos.getStorageLocation() != null && !cos.getStorageLocation().isBlank()) {
                    return Paths.get(cos.getStorageLocation().trim());
                }
            }
            if (storage.getPath() != null && !storage.getPath().trim().isEmpty()) {
                return Paths.get(storage.getPath().trim());
            }
        }
        return Paths.get(DEFAULT_IMPORT_DIR);
    }

    public List<Path> resolveSourceFiles(MongoSecureImportConfig config) {
        if (config != null && config.getStorage() != null && "cos".equalsIgnoreCase(config.getStorage().getType())) {
            Long cosId = config.getStorage().getId() != null ? config.getStorage().getId() : config.getStorage().getCosId();
            if (cosId != null && cosConnectionService != null && ibmCosService != null) {
                CosConnection cos = cosConnectionService.getConnection(cosId);
                if (cos != null && !"Local".equalsIgnoreCase(cos.getStorageType())) {
                    try {
                        List<Map<String, Object>> objects = ibmCosService.listObjects(cos, null);
                        Path tempDir = Files.createTempDirectory("cos-mongo-import-staging");
                        List<Path> stagedFiles = new ArrayList<>();
                        for (Map<String, Object> obj : objects) {
                            String name = (String) obj.get("name");
                            if (name != null && (name.toLowerCase().endsWith(".bson.enc") || name.toLowerCase().endsWith(".bson"))) {
                                Path stagingFile = tempDir.resolve(name);
                                ibmCosService.downloadFile(cos, name, stagingFile);
                                stagedFiles.add(stagingFile);
                            }
                        }
                        if (!stagedFiles.isEmpty()) {
                            log.info("Downloaded {} Mongo backup package(s) from COS to temporary staging", stagedFiles.size());
                            return com.enterprise.seedm.util.BackupFileUtils.sortBackupFiles(stagedFiles);
                        }
                    } catch (Exception e) {
                        log.error("Failed to download Mongo package from COS bucket", e);
                    }
                }
            }
        }

        Path basePath = resolveStoragePath(config != null ? config.getStorage() : null);

        if (config != null && config.getStorage() != null && config.getStorage().getFileName() != null && !config.getStorage().getFileName().trim().isEmpty()) {
            String fileName = config.getStorage().getFileName().trim();
            Path specificFile = Files.isDirectory(basePath) ? basePath.resolve(fileName) : Paths.get(fileName);
            if (Files.isRegularFile(specificFile)) {
                List<Path> parts = com.enterprise.seedm.util.BackupFileUtils.findBackupFiles(specificFile, "secure-mongo-export", ".bson.enc", ".bson");
                if (parts != null && !parts.isEmpty()) {
                    return parts;
                }
                return List.of(specificFile);
            }
        }

        if (Files.isRegularFile(basePath)) {
            List<Path> parts = com.enterprise.seedm.util.BackupFileUtils.findBackupFiles(basePath, "secure-mongo-export", ".bson.enc", ".bson");
            if (parts != null && !parts.isEmpty()) {
                return parts;
            }
            return List.of(basePath);
        }

        if (Files.isDirectory(basePath)) {
            List<Path> files = com.enterprise.seedm.util.BackupFileUtils.findBackupFiles(basePath, "secure-mongo-export", ".bson.enc", ".bson");
            if (!files.isEmpty()) {
                return files;
            }

            try (Stream<Path> stream = Files.list(basePath)) {
                List<Path> anyBson = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".bson.enc") || p.getFileName().toString().toLowerCase().endsWith(".bson"))
                        .collect(Collectors.toList());
                if (!anyBson.isEmpty()) {
                    return com.enterprise.seedm.util.BackupFileUtils.sortBackupFiles(anyBson);
                }
            } catch (IOException e) {
                log.warn("Error listing directory for Mongo backup files: {}", basePath, e);
            }
        }

        Path defaultEnc = basePath.resolve("secure-mongo-export.bson.enc");
        if (Files.exists(defaultEnc)) return List.of(defaultEnc);
        return List.of(basePath.resolve("secure-mongo-export.bson"));
    }

    public Path resolveSourceFile(MongoSecureImportConfig config) {
        List<Path> files = resolveSourceFiles(config);
        return files.get(0);
    }

    private MongoClient createClient(MongoSecureImportConfig config) {
        if (config.getDest() != null) {
            if (config.getDest().getId() != null) {
                DbConnection conn = dbConnectionService.getConnection(config.getDest().getId());
                if (conn != null) {
                    return mongoConnectionHelper.createClient(conn);
                }
            }
            if (config.getDest().getUrl() != null && !config.getDest().getUrl().isEmpty()) {
                return mongoConnectionHelper.createClient(config.getDest().getUrl(), config.getDest().getUsername(), config.getDest().getPassword());
            }
        }
        throw new IllegalArgumentException("Target MongoDB connection URL or ID is missing");
    }

    private String resolveMongoDatabase(MongoSecureImportConfig config) {
        if (config.getDest() != null && config.getDest().getDatabase() != null) {
            return config.getDest().getDatabase();
        }
        return "admin";
    }

    private void saveJobRecord(String executionId, MongoSecureImportConfig config, String status, String errorMessage) {
        if (jobRepository == null) return;
        try {
            MongoSecureImportProgress progress = progressMap.get(executionId);
            MongoSecureImportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new MongoSecureImportJob();
                job.setExecutionId(executionId);
                job.setJobName((config != null && config.getJobName() != null && !config.getJobName().trim().isEmpty()) ? config.getJobName().trim() : executionId);
                job.setCreatedAt(System.currentTimeMillis());
            }
            job.setStatus(status);
            job.setErrorMessage(errorMessage);
            if (progress != null) {
                job.setTotalCollections(progress.totalCollections.get());
                job.setProcessedCollections(progress.processedCollections.get());
                job.setTotalRecords(progress.totalRecords.get());
                if (progress.collectionProgress != null) {
                    job.setProgressDetails(objectMapper.writeValueAsString(progress.collectionProgress));
                }
            }
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                job.setCompletedAt(System.currentTimeMillis());
            }
            if (config != null) {
                job.setConfigDetails(objectMapper.writeValueAsString(config));
            }
            jobRepository.saveAndFlush(job);
        } catch (Exception e) {
            log.error("Failed to save MongoSecureImportJob record", e);
        }
    }

    public Map<String, Object> getProgress(String executionId) {
        MongoSecureImportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            return progress.toMap();
        }

        if (jobRepository == null) {
            return Map.of("status", "NOT_FOUND");
        }
        MongoSecureImportJob job = jobRepository.findByExecutionId(executionId);
        if (job == null) {
            return Map.of("status", "NOT_FOUND");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("executionId", job.getExecutionId());
        map.put("jobName", job.getJobName());
        map.put("status", job.getStatus());
        map.put("errorMessage", job.getErrorMessage());
        map.put("startTime", job.getCreatedAt());
        map.put("completedTime", job.getCompletedAt() != null ? job.getCompletedAt() : 0);
        map.put("endTime", job.getCompletedAt() != null ? job.getCompletedAt() : 0);
        map.put("totalCollections", job.getTotalCollections() != null ? job.getTotalCollections() : 0);
        map.put("processedCollections", job.getProcessedCollections() != null ? job.getProcessedCollections() : 0);
        map.put("totalTables", job.getTotalCollections() != null ? job.getTotalCollections() : 0);
        map.put("processedTables", job.getProcessedCollections() != null ? job.getProcessedCollections() : 0);
        map.put("totalRecords", job.getTotalRecords() != null ? job.getTotalRecords() : 0L);

        List<Map<String, Object>> collectionProgress = new ArrayList<>();
        List<String> completedCollections = new ArrayList<>();
        if (job.getProgressDetails() != null && !job.getProgressDetails().isBlank()) {
            try {
                collectionProgress = objectMapper.readValue(job.getProgressDetails(), List.class);
                for (Map<String, Object> cp : collectionProgress) {
                    if ("COMPLETED".equals(cp.get("status")) && cp.get("tableName") != null) {
                        completedCollections.add(cp.get("tableName").toString());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse progress details for mongo import: {}", executionId, e);
            }
        }
        map.put("tableProgress", collectionProgress);
        map.put("completedTables", completedCollections);
        long start = job.getCreatedAt() != null ? job.getCreatedAt() : 0;
        long end = job.getCompletedAt() != null ? job.getCompletedAt() : System.currentTimeMillis();
        map.put("elapsedSeconds", (end - start) / 1000);
        return map;
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> list = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // From memory
        for (Map.Entry<String, MongoSecureImportProgress> entry : progressMap.entrySet()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", entry.getKey());
            map.put("jobName", entry.getKey());
            map.put("status", entry.getValue().status);
            map.put("startTime", entry.getValue().startTime);
            map.put("completedTime", entry.getValue().completedTime);
            list.add(map);
            seenIds.add(entry.getKey());
        }

        // From DB
        if (jobRepository != null) {
            for (MongoSecureImportJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
                if (!seenIds.contains(job.getExecutionId())) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", job.getExecutionId());
                    map.put("jobName", job.getJobName());
                    map.put("status", job.getStatus());
                    map.put("startTime", job.getCreatedAt());
                    map.put("completedTime", job.getCompletedAt());
                    list.add(map);
                    seenIds.add(job.getExecutionId());
                }
            }
        }
        return list;
    }
}
