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
                                    return name != null && (name.endsWith(".bson") || name.endsWith(".bson.enc") || name.endsWith(".enc"));
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
                    Map<String, Object> fInfo = new HashMap<>();
                    fInfo.put("name", dirPath.getFileName().toString());
                    fInfo.put("sizeBytes", Files.size(dirPath));
                    fInfo.put("encrypted", dirPath.toString().endsWith(".enc"));
                    filesList.add(fInfo);
                } else if (Files.isDirectory(dirPath)) {
                    try (Stream<Path> stream = Files.list(dirPath)) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".bson") || p.toString().endsWith(".bson.enc") || p.toString().endsWith(".enc"))
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
            log.error("Failed to scan storage for Mongo import", e);
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return response;
        }
    }

    public void validateSecretKey(MongoSecureImportConfig config, String secretKey) {
        Path sourceFile = resolveSourceFile(config);
        if (!Files.exists(sourceFile)) {
            throw new IllegalArgumentException("Encrypted MongoDB backup file not found: " + sourceFile);
        }

        if (!sourceFile.toString().endsWith(".enc")) {
            return; // Plain file
        }

        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret key is required for encrypted file");
        }

        try (InputStream in = Files.newInputStream(sourceFile)) {
            byte[] iv = new byte[16];
            int bytesRead = in.read(iv);
            if (bytesRead < 16) {
                throw new IllegalArgumentException("File is too small to be a valid encrypted archive");
            }

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(secretKey.trim().getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec);

            byte[] encryptedBuffer = new byte[64];
            int readEnc = in.read(encryptedBuffer);
            if (readEnc > 0) {
                byte[] decrypted = cipher.update(encryptedBuffer, 0, readEnc);
                if (decrypted != null && decrypted.length >= MAGIC_HEADER.length) {
                    for (int i = 0; i < MAGIC_HEADER.length; i++) {
                        if (decrypted[i] != MAGIC_HEADER[i]) {
                            throw new IllegalArgumentException("Invalid secret key. Header verification failed.");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Secret key validation failed for file {}: {}", sourceFile, e.getMessage());
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

            Path sourceFile = resolveSourceFile(config);
            if (!Files.exists(sourceFile)) {
                throw new IllegalArgumentException("MongoDB import file not found: " + sourceFile);
            }

            String dbName = resolveMongoDatabase(config);

            if (dbName == null) {
                throw new IllegalArgumentException("Target MongoDB database name is missing");
            }

            boolean isEncrypted = sourceFile.toString().endsWith(".enc");
            try (MongoClient mongoClient = createClient(config);
                 InputStream rawIn = new BufferedInputStream(Files.newInputStream(sourceFile), 128 * 1024)) {

                MongoDatabase database = mongoClient.getDatabase(dbName);
                InputStream streamToRead = rawIn;

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
                    streamToRead = new CipherInputStream(rawIn, cipher);
                }

                try (DataInputStream dataIn = new DataInputStream(new BufferedInputStream(streamToRead, 128 * 1024))) {
                    // Check magic header
                    byte[] header = new byte[MAGIC_HEADER.length];
                    dataIn.readFully(header);
                    if (!Arrays.equals(header, MAGIC_HEADER)) {
                        throw new IllegalArgumentException("Invalid file format: MAGIC header mismatch");
                    }

                    boolean reading = true;
                    String currentCollection = null;
                    List<Document> batch = new ArrayList<>(1000);
                    Set<String> droppedCollections = new HashSet<>();

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
                                markCollectionComplete(progress, currentCollection);
                                processedCollections++;
                                progress.processedCollections.set(processedCollections);
                                currentCollection = null;
                            }
                        }
                    }

                    if (currentCollection != null && !batch.isEmpty()) {
                        flushBatch(database.getCollection(currentCollection), batch);
                        markCollectionComplete(progress, currentCollection);
                        processedCollections++;
                        progress.processedCollections.set(processedCollections);
                    }
                }

                progress.status = "COMPLETED";
                progress.completedTime = System.currentTimeMillis();
                saveJobRecord(executionId, config, "COMPLETED", null);
                log.info("Mongo secure import completed for execution ID: {}", executionId);
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
            if ("cos".equalsIgnoreCase(storage.getType()) && storage.getId() != null) {
                CosConnection cos = cosConnectionService.getConnection(storage.getId());
                if (cos != null && cos.getStorageLocation() != null) {
                    return Paths.get(cos.getStorageLocation());
                }
            }
            if (storage.getPath() != null && !storage.getPath().trim().isEmpty()) {
                return Paths.get(storage.getPath().trim());
            }
        }
        return Paths.get(DEFAULT_IMPORT_DIR);
    }

    private Path resolveSourceFile(MongoSecureImportConfig config) {
        if (config.getStorage() != null && "cos".equalsIgnoreCase(config.getStorage().getType())) {
            Long cosId = config.getStorage().getId() != null ? config.getStorage().getId() : config.getStorage().getCosId();
            if (cosId != null && cosConnectionService != null && ibmCosService != null) {
                CosConnection cos = cosConnectionService.getConnection(cosId);
                if (cos != null && !"Local".equalsIgnoreCase(cos.getStorageType())) {
                    String fileName = config.getStorage().getFileName() != null && !config.getStorage().getFileName().trim().isEmpty()
                            ? config.getStorage().getFileName().trim() : "secure-mongo-export.bson.enc";
                    try {
                        Path tempDir = Files.createTempDirectory("cos-mongo-import-staging");
                        Path stagingFile = tempDir.resolve(fileName);
                        ibmCosService.downloadFile(cos, fileName, stagingFile);
                        log.info("Downloaded COS Mongo package to staging file: {}", stagingFile);
                        return stagingFile;
                    } catch (Exception e) {
                        log.error("Failed to download Mongo package from COS bucket", e);
                    }
                }
            }
        }

        Path basePath = resolveStoragePath(config.getStorage());
        if (config.getStorage() != null && config.getStorage().getFileName() != null && !config.getStorage().getFileName().trim().isEmpty()) {
            String fileName = config.getStorage().getFileName().trim();
            if (Files.isDirectory(basePath)) {
                return basePath.resolve(fileName);
            }
            return Paths.get(fileName);
        }
        if (Files.isRegularFile(basePath)) {
            return basePath;
        }
        Path defaultEnc = basePath.resolve("secure-mongo-export.bson.enc");
        if (Files.exists(defaultEnc)) return defaultEnc;
        return basePath.resolve("secure-mongo-export.bson");
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
        try {
            MongoSecureImportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new MongoSecureImportJob();
                job.setExecutionId(executionId);
                job.setJobName(config.getJobName() != null ? config.getJobName() : "Mongo Secure Import");
                job.setCreatedAt(System.currentTimeMillis());
            }
            job.setStatus(status);
            job.setErrorMessage(errorMessage);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                job.setCompletedAt(System.currentTimeMillis());
            }
            job.setConfigDetails(objectMapper.writeValueAsString(config));
            jobRepository.save(job);
        } catch (Exception e) {
            log.error("Failed to save MongoSecureImportJob record", e);
        }
    }

    public Map<String, Object> getProgress(String executionId) {
        MongoSecureImportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            return progress.toMap();
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
        return map;
    }

    public List<Map<String, Object>> getAllExecutions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (MongoSecureImportJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", job.getExecutionId());
            map.put("jobName", job.getJobName());
            map.put("status", job.getStatus());
            map.put("startTime", job.getCreatedAt());
            map.put("completedTime", job.getCompletedAt());
            list.add(map);
        }
        return list;
    }
}
