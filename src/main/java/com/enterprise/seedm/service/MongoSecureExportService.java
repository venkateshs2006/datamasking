package com.enterprise.seedm.service;

import com.enterprise.seedm.model.CosConnection;
import com.enterprise.seedm.model.DbConnection;
import com.enterprise.seedm.model.MongoSecureExportConfig;
import com.enterprise.seedm.model.MongoSecureExportJob;
import com.enterprise.seedm.repository.MongoSecureExportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonBinaryWriter;
import org.bson.Document;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class MongoSecureExportService {

    private final DbConnectionService dbConnectionService;
    private final CosConnectionService cosConnectionService;
    private final FormatPreservingEncryptionService fpeService;
    private final MongoSecureExportJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final MongoConnectionHelper mongoConnectionHelper;

    private static final String DEFAULT_EXPORT_DIR = "secure-export";
    private static final byte[] MAGIC_HEADER = "MONGOBSON1\n".getBytes(StandardCharsets.UTF_8);
    private static final byte MARKER_COLLECTION_START = 0x01;
    private static final byte MARKER_DOC_RECORD = 0x02;
    private static final byte MARKER_COLLECTION_END = 0x03;
    private static final byte MARKER_EOF = (byte) 0xFF;

    private final DocumentCodec documentCodec = new DocumentCodec();

    public static class MongoSecureExportProgress {
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

    private final Map<String, MongoSecureExportProgress> progressMap = new ConcurrentHashMap<>();

    public String processMongoExport(String executionId, MongoSecureExportConfig config) {
        MongoSecureExportProgress progress = progressMap.computeIfAbsent(executionId, k -> new MongoSecureExportProgress());
        progress.status = "RUNNING";
        progress.startTime = System.currentTimeMillis();

        int processedCount = 0;
        List<String> collectionNames = new ArrayList<>();

        try {
            saveJobRecord(executionId, config, "RUNNING", null);

            String dbName = resolveMongoDatabase(config);
            String saltKey = config.getRules() != null ? config.getRules().getMaskingKey() : null;

            if (dbName == null) {
                throw new IllegalArgumentException("MongoDB source database name is missing");
            }

            try (MongoClient mongoClient = createClient(config)) {
                MongoDatabase database = mongoClient.getDatabase(dbName);

                List<String> allCollections = new ArrayList<>();
                database.listCollectionNames().into(allCollections);

                List<String> userCollections = null;
                if (config.getRules() != null) {
                    if (config.getRules().getTargetCollections() != null && !config.getRules().getTargetCollections().isEmpty()) {
                        userCollections = config.getRules().getTargetCollections();
                    } else if (config.getRules().getTargetTables() != null && !config.getRules().getTargetTables().isEmpty()) {
                        userCollections = config.getRules().getTargetTables();
                    }
                }
                if (userCollections != null) {
                    for (String c : userCollections) {
                        if (allCollections.contains(c)) {
                            collectionNames.add(c);
                        }
                    }
                } else {
                    collectionNames.addAll(allCollections);
                }

                int totalCollections = collectionNames.size();
                progress.totalCollections.set(totalCollections);
                progress.collectionProgress.clear();
                for (String coll : collectionNames) {
                    Map<String, Object> entry = new ConcurrentHashMap<>();
                    entry.put("tableName", coll); // Compatible with UI
                    entry.put("readCount", 0);
                    entry.put("writeCount", 0);
                    entry.put("status", "PENDING");
                    progress.collectionProgress.add(entry);
                }

                Path destDir = resolveDestDirectory(config);
                Files.createDirectories(destDir);
                Path tempBsonPath = destDir.resolve("secure-mongo-export.bson");

                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempBsonPath), 128 * 1024);
                     DataOutputStream dataOut = new DataOutputStream(out)) {

                    dataOut.write(MAGIC_HEADER);

                    for (String collName : collectionNames) {
                        for (Map<String, Object> cp : progress.collectionProgress) {
                            if (collName.equals(cp.get("tableName"))) {
                                cp.put("status", "RUNNING");
                                break;
                            }
                        }

                        int docCount = exportCollection(dataOut, database.getCollection(collName), collName, config, saltKey, progress);
                        processedCount++;
                        progress.processedCollections.set(processedCount);
                        progress.completedCollections.add(collName);

                        for (Map<String, Object> cp : progress.collectionProgress) {
                            if (collName.equals(cp.get("tableName"))) {
                                cp.put("status", "COMPLETED");
                                cp.put("readCount", docCount);
                                cp.put("writeCount", docCount);
                                break;
                            }
                        }
                    }

                    dataOut.writeByte(MARKER_EOF);
                    dataOut.flush();
                }

                // Encrypt output BSON file
                if (saltKey != null && !saltKey.trim().isEmpty()) {
                    Path encFilePath = destDir.resolve("secure-mongo-export.bson.enc");
                    encryptFileWithSalt(tempBsonPath, encFilePath, saltKey);
                    try {
                        Files.deleteIfExists(tempBsonPath);
                    } catch (Exception ex) {
                        log.warn("Could not delete temporary BSON file: {}", ex.getMessage());
                    }
                }

                progress.status = "COMPLETED";
                progress.completedTime = System.currentTimeMillis();
                saveJobRecord(executionId, config, "COMPLETED", null);
                log.info("Mongo Secure Export completed successfully. Output in: {}", destDir);
                return "COMPLETED";
            }
        } catch (Exception e) {
            log.error("Mongo Secure Export execution failed: {}", executionId, e);
            progress.status = "FAILED";
            progress.errorMessage = e.getMessage();
            progress.completedTime = System.currentTimeMillis();
            saveJobRecord(executionId, config, "FAILED", e.getMessage());
            throw new RuntimeException("Mongo Secure Export failed: " + e.getMessage(), e);
        }
    }

    private int exportCollection(DataOutputStream dataOut, MongoCollection<Document> collection, String collectionName,
                                 MongoSecureExportConfig config, String saltKey, MongoSecureExportProgress progress) throws IOException {
        byte[] collBytes = collectionName.getBytes(StandardCharsets.UTF_8);
        dataOut.writeByte(MARKER_COLLECTION_START);
        dataOut.writeShort(collBytes.length);
        dataOut.write(collBytes);

        int count = 0;
        for (Document originalDoc : collection.find()) {
            count++;
            if (progress != null) {
                progress.totalRecords.incrementAndGet();
            }

            Document maskedDoc = maskDocument(originalDoc, collectionName, config, saltKey);
            byte[] bsonBytes = encodeDocumentToBson(maskedDoc);

            dataOut.writeByte(MARKER_DOC_RECORD);
            dataOut.writeInt(bsonBytes.length);
            dataOut.write(bsonBytes);
        }

        dataOut.writeByte(MARKER_COLLECTION_END);
        return count;
    }

    private byte[] encodeDocumentToBson(Document doc) {
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        BsonBinaryWriter writer = new BsonBinaryWriter(buffer);
        documentCodec.encode(writer, doc, EncoderContext.builder().build());
        return buffer.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private Document maskDocument(Document originalDoc, String collectionName, MongoSecureExportConfig config, String saltKey) {
        if (originalDoc == null) return null;
        Document copy = new Document(originalDoc);

        if (config.getRules() == null) {
            return copy;
        }

        MongoSecureExportConfig.RulesConfig rules = config.getRules();

        // 1. Check map-based rules
        if (rules.getMaskingFields() != null && rules.getMaskingFields().containsKey(collectionName)) {
            for (String fieldPath : rules.getMaskingFields().get(collectionName)) {
                maskFieldInDocument(copy, fieldPath, "SFD", saltKey);
            }
        }
        if (rules.getPartialMaskingFields() != null && rules.getPartialMaskingFields().containsKey(collectionName)) {
            for (String fieldPath : rules.getPartialMaskingFields().get(collectionName)) {
                maskFieldInDocument(copy, fieldPath, "PMD", saltKey);
            }
        }

        // 2. Check dot-notated rules
        if (rules.getMaskingColumns() != null) {
            for (String col : rules.getMaskingColumns()) {
                if (col.startsWith(collectionName + ".")) {
                    String fieldPath = col.substring(collectionName.length() + 1);
                    maskFieldInDocument(copy, fieldPath, "SFD", saltKey);
                }
            }
        }
        if (rules.getPartialMaskingColumns() != null) {
            for (String col : rules.getPartialMaskingColumns()) {
                if (col.startsWith(collectionName + ".")) {
                    String fieldPath = col.substring(collectionName.length() + 1);
                    maskFieldInDocument(copy, fieldPath, "PMD", saltKey);
                }
            }
        }
        if (rules.getConstraintColumns() != null) {
            for (String col : rules.getConstraintColumns()) {
                if (col.startsWith(collectionName + ".")) {
                    String fieldPath = col.substring(collectionName.length() + 1);
                    maskFieldInDocument(copy, fieldPath, "FPH", saltKey);
                }
            }
        }

        return copy;
    }

    @SuppressWarnings("unchecked")
    private void maskFieldInDocument(Map<String, Object> doc, String fieldPath, String ruleType, String saltKey) {
        if (doc == null || fieldPath == null) return;
        String[] parts = fieldPath.split("\\.", 2);
        String currentKey = parts[0];

        if (!doc.containsKey(currentKey)) {
            return;
        }

        Object currentVal = doc.get(currentKey);

        if (parts.length == 1) {
            // Leaf field to mask
            if (currentVal != null) {
                if ("PMD".equalsIgnoreCase(ruleType)) {
                    String strVal = currentVal.toString();
                    if (strVal.length() > 4) {
                        doc.put(currentKey, "****" + strVal.substring(strVal.length() - 4));
                    } else {
                        doc.put(currentKey, "****");
                    }
                } else {
                    Object masked = applyFpeMask(currentVal, saltKey);
                    doc.put(currentKey, masked);
                }
            }
        } else {
            // Nested path
            String remainingPath = parts[1];
            if (currentVal instanceof Map) {
                maskFieldInDocument((Map<String, Object>) currentVal, remainingPath, ruleType, saltKey);
            } else if (currentVal instanceof List) {
                List<?> list = (List<?>) currentVal;
                for (Object item : list) {
                    if (item instanceof Map) {
                        maskFieldInDocument((Map<String, Object>) item, remainingPath, ruleType, saltKey);
                    }
                }
            }
        }
    }

    private Object applyFpeMask(Object val, String saltKey) {
        if (val == null) return null;
        try {
            if (val instanceof Integer) {
                return fpeService.encrypt(val, "integer", saltKey);
            } else if (val instanceof Long) {
                return fpeService.encrypt(val, "long", saltKey);
            } else if (val instanceof Double) {
                return fpeService.encrypt(val, "double", saltKey);
            } else if (val instanceof Float) {
                return fpeService.encrypt(val, "float", saltKey);
            } else if (val instanceof Boolean) {
                return fpeService.encrypt(val, "boolean", saltKey);
            } else {
                return fpeService.encrypt(val.toString(), "string", saltKey);
            }
        } catch (Exception e) {
            log.warn("FPE masking failed for value {}, returning original", val);
            return val;
        }
    }

    public void encryptFileWithSalt(Path sourceFile, Path targetEncryptedFile, String saltKey) throws Exception {
        if (!Files.exists(sourceFile)) {
            log.warn("Source file does not exist for encryption: {}", sourceFile);
            return;
        }

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(saltKey.trim().getBytes(StandardCharsets.UTF_8));
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        try (InputStream in = Files.newInputStream(sourceFile);
             OutputStream out = Files.newOutputStream(targetEncryptedFile)) {
            out.write(iv);
            try (CipherOutputStream cipherOut = new CipherOutputStream(out, cipher)) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    cipherOut.write(buffer, 0, bytesRead);
                }
                cipherOut.flush();
            }
        }
    }

    private MongoClient createClient(MongoSecureExportConfig config) {
        if (config.getSource() != null) {
            if (config.getSource().getId() != null) {
                DbConnection conn = dbConnectionService.getConnection(config.getSource().getId());
                if (conn != null) {
                    return mongoConnectionHelper.createClient(conn);
                }
            }
            if (config.getSource().getUrl() != null && !config.getSource().getUrl().isEmpty()) {
                return mongoConnectionHelper.createClient(config.getSource().getUrl(), config.getSource().getUsername(), config.getSource().getPassword());
            }
        }
        throw new IllegalArgumentException("MongoDB source connection URL or ID is missing");
    }

    private String resolveMongoDatabase(MongoSecureExportConfig config) {
        if (config.getSource() != null) {
            if (config.getSource().getDatabase() != null && !config.getSource().getDatabase().trim().isEmpty()) {
                return config.getSource().getDatabase().trim();
            }
            if (config.getSource().getSchema() != null && !config.getSource().getSchema().trim().isEmpty()) {
                return config.getSource().getSchema().trim();
            }
        }
        return "admin";
    }

    private Path resolveDestDirectory(MongoSecureExportConfig config) {
        if (config.getDest() != null) {
            if ("cos".equalsIgnoreCase(config.getDest().getType()) && config.getDest().getCosId() != null) {
                CosConnection cos = cosConnectionService.getConnection(config.getDest().getCosId());
                if (cos != null && cos.getStorageLocation() != null) {
                    return Paths.get(cos.getStorageLocation());
                }
            }
            if (config.getDest().getDestDir() != null && !config.getDest().getDestDir().trim().isEmpty()) {
                return Paths.get(config.getDest().getDestDir().trim());
            }
            if (config.getDest().getPath() != null && !config.getDest().getPath().trim().isEmpty()) {
                return Paths.get(config.getDest().getPath().trim());
            }
        }
        return Paths.get(DEFAULT_EXPORT_DIR);
    }

    private void saveJobRecord(String executionId, MongoSecureExportConfig config, String status, String errorMessage) {
        try {
            MongoSecureExportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new MongoSecureExportJob();
                job.setExecutionId(executionId);
                job.setJobName(config.getJobName() != null ? config.getJobName() : "Mongo Secure Export");
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
            log.error("Failed to save MongoSecureExportJob record", e);
        }
    }

    public Map<String, Object> getProgress(String executionId) {
        MongoSecureExportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            return progress.toMap();
        }

        MongoSecureExportJob job = jobRepository.findByExecutionId(executionId);
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
        for (MongoSecureExportJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
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
