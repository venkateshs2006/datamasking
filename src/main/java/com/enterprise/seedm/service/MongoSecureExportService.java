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
@Slf4j
public class MongoSecureExportService {

    private final DbConnectionService dbConnectionService;
    private final CosConnectionService cosConnectionService;
    private final IbmCosService ibmCosService;
    private final FormatPreservingEncryptionService fpeService;
    private final MongoSecureExportJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final MongoConnectionHelper mongoConnectionHelper;
    private final DataMaskingService dataMaskingService;

    public MongoSecureExportService(DbConnectionService dbConnectionService, CosConnectionService cosConnectionService, IbmCosService ibmCosService, FormatPreservingEncryptionService fpeService, MongoSecureExportJobRepository jobRepository, ObjectMapper objectMapper, MongoConnectionHelper mongoConnectionHelper) {
        this(dbConnectionService, cosConnectionService, ibmCosService, fpeService, jobRepository, objectMapper, mongoConnectionHelper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MongoSecureExportService(DbConnectionService dbConnectionService, CosConnectionService cosConnectionService, IbmCosService ibmCosService, FormatPreservingEncryptionService fpeService, MongoSecureExportJobRepository jobRepository, ObjectMapper objectMapper, MongoConnectionHelper mongoConnectionHelper, @org.springframework.context.annotation.Lazy DataMaskingService dataMaskingService) {
        this.dbConnectionService = dbConnectionService;
        this.cosConnectionService = cosConnectionService;
        this.ibmCosService = ibmCosService;
        this.fpeService = fpeService;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        this.mongoConnectionHelper = mongoConnectionHelper;
        this.dataMaskingService = dataMaskingService;
    }

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
        public volatile String fileEncryptionKey = null;
        public volatile String status = "PENDING";
        public volatile String errorMessage = null;
        public volatile long startTime = System.currentTimeMillis();
        public volatile long completedTime = 0;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("status", status);
            map.put("fileEncryptionKey", fileEncryptionKey);
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
            String fileEncryptionKey = (config.getFileEncryptionKey() != null && !config.getFileEncryptionKey().trim().isEmpty())
                    ? config.getFileEncryptionKey().trim()
                    : saltKey;
            progress.fileEncryptionKey = fileEncryptionKey;

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

                String encryptKey = (fileEncryptionKey != null && !fileEncryptionKey.trim().isEmpty()) ? fileEncryptionKey : saltKey;
                long maxFileSizeBytes = resolveMaxFileSizeBytes();
                List<Path> finalExportFiles;

                try (MongoBsonRotator rotator = new MongoBsonRotator(destDir, "secure-mongo-export", encryptKey, maxFileSizeBytes)) {
                    for (String collName : collectionNames) {
                        for (Map<String, Object> cp : progress.collectionProgress) {
                            if (collName.equals(cp.get("tableName"))) {
                                cp.put("status", "RUNNING");
                                break;
                            }
                        }

                        int docCount = exportCollection(rotator, database.getCollection(collName), collName, config, saltKey, progress);
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

                    rotator.close();
                    finalExportFiles = rotator.getExportedFiles();
                }

                // Upload to COS bucket if destination is Cloud Object Storage
                if (config.getDest() != null && "cos".equalsIgnoreCase(config.getDest().getType()) && config.getDest().getCosId() != null) {
                    try {
                        CosConnection cosConn = cosConnectionService.getConnection(config.getDest().getCosId());
                        if (cosConn != null && !"Local".equalsIgnoreCase(cosConn.getStorageType()) && ibmCosService != null) {
                            for (Path exportFile : finalExportFiles) {
                                if (Files.exists(exportFile)) {
                                    ibmCosService.uploadFile(cosConn, exportFile.getFileName().toString(), exportFile);
                                    log.info("Uploaded Mongo secure export part to COS bucket {}: {}", ibmCosService.getEffectiveBucketName(cosConn), exportFile.getFileName());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to upload Mongo secure export parts to COS bucket", e);
                    }
                }

                progress.status = "COMPLETED";
                progress.completedTime = System.currentTimeMillis();
                saveJobRecord(executionId, config, "COMPLETED", null);
                log.info("Mongo Secure Export completed successfully with {} backup file(s). Output in: {}", finalExportFiles.size(), destDir);
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

    public static class MongoBsonRotator implements AutoCloseable {
        private final Path destDir;
        private final String baseName;
        private final String encryptKey;
        private final long maxSizeBytes;
        private final List<Path> exportedFiles = new ArrayList<>();

        private int partNumber = 1;
        private long currentFileBytes = 0;
        private Path currentTempPath;
        private DataOutputStream currentDataOut;
        private String currentOpenCollection = null;

        public MongoBsonRotator(Path destDir, String baseName, String encryptKey, long maxSizeBytes) throws IOException {
            this.destDir = destDir;
            this.baseName = baseName;
            this.encryptKey = encryptKey;
            this.maxSizeBytes = maxSizeBytes;
            openPart(1, false);
        }

        private void openPart(int partNum, boolean isRotated) throws IOException {
            this.partNumber = partNum;
            String tempFileName = isRotated ? (baseName + "-part" + partNum + ".bson") : (baseName + ".bson");
            this.currentTempPath = destDir.resolve(tempFileName);
            this.currentDataOut = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(currentTempPath), 128 * 1024));
            this.currentDataOut.write(MAGIC_HEADER);
            this.currentFileBytes = MAGIC_HEADER.length;
        }

        public void startCollection(String collName) throws IOException {
            if (currentOpenCollection != null) {
                endCollection();
            }
            currentOpenCollection = collName;
            byte[] collBytes = collName.getBytes(StandardCharsets.UTF_8);
            currentDataOut.writeByte(MARKER_COLLECTION_START);
            currentDataOut.writeShort(collBytes.length);
            currentDataOut.write(collBytes);
            currentFileBytes += 1 + 2 + collBytes.length;
        }

        public void writeDoc(byte[] bsonBytes) throws Exception {
            int docRecordSize = 1 + 4 + bsonBytes.length;
            if (currentFileBytes > MAGIC_HEADER.length && (currentFileBytes + docRecordSize >= maxSizeBytes)) {
                rotate();
            }
            currentDataOut.writeByte(MARKER_DOC_RECORD);
            currentDataOut.writeInt(bsonBytes.length);
            currentDataOut.write(bsonBytes);
            currentFileBytes += docRecordSize;
        }

        public void endCollection() throws IOException {
            if (currentOpenCollection != null) {
                currentDataOut.writeByte(MARKER_COLLECTION_END);
                currentFileBytes += 1;
                currentOpenCollection = null;
            }
        }

        private void rotate() throws Exception {
            if (currentOpenCollection != null) {
                currentDataOut.writeByte(MARKER_COLLECTION_END);
            }
            currentDataOut.writeByte(MARKER_EOF);
            currentDataOut.flush();
            currentDataOut.close();

            Path targetFile;
            if (partNumber == 1) {
                targetFile = destDir.resolve(baseName + "-part1.bson.enc");
            } else {
                targetFile = destDir.resolve(baseName + "-part" + partNumber + ".bson.enc");
            }

            if (encryptKey != null && !encryptKey.trim().isEmpty()) {
                com.enterprise.seedm.util.BackupFileUtils.encryptFileWithSalt(currentTempPath, targetFile, encryptKey);
                try {
                    Files.deleteIfExists(currentTempPath);
                } catch (Exception ex) {
                    log.warn("Could not delete temp BSON file: {}", ex.getMessage());
                }
                exportedFiles.add(targetFile);
            } else {
                Path plainTarget = destDir.resolve(baseName + "-part" + partNumber + ".bson");
                if (!currentTempPath.equals(plainTarget)) {
                    Files.move(currentTempPath, plainTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    exportedFiles.add(plainTarget);
                } else {
                    exportedFiles.add(currentTempPath);
                }
            }

            openPart(partNumber + 1, true);

            if (currentOpenCollection != null) {
                byte[] collBytes = currentOpenCollection.getBytes(StandardCharsets.UTF_8);
                currentDataOut.writeByte(MARKER_COLLECTION_START);
                currentDataOut.writeShort(collBytes.length);
                currentDataOut.write(collBytes);
                currentFileBytes += 1 + 2 + collBytes.length;
            }
        }

        @Override
        public void close() throws Exception {
            if (currentDataOut != null) {
                endCollection();
                currentDataOut.writeByte(MARKER_EOF);
                currentDataOut.flush();
                currentDataOut.close();

                Path targetFile;
                if (partNumber == 1 && exportedFiles.isEmpty()) {
                    targetFile = destDir.resolve(baseName + ".bson.enc");
                } else {
                    targetFile = destDir.resolve(baseName + "-part" + partNumber + ".bson.enc");
                }

                if (encryptKey != null && !encryptKey.trim().isEmpty()) {
                    com.enterprise.seedm.util.BackupFileUtils.encryptFileWithSalt(currentTempPath, targetFile, encryptKey);
                    try {
                        Files.deleteIfExists(currentTempPath);
                    } catch (Exception ex) {
                        log.warn("Could not delete temp BSON file: {}", ex.getMessage());
                    }
                    exportedFiles.add(targetFile);
                } else {
                    Path plainTarget = (partNumber == 1 && exportedFiles.isEmpty())
                            ? destDir.resolve(baseName + ".bson")
                            : destDir.resolve(baseName + "-part" + partNumber + ".bson");
                    if (!currentTempPath.equals(plainTarget)) {
                        Files.move(currentTempPath, plainTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        exportedFiles.add(plainTarget);
                    } else {
                        exportedFiles.add(currentTempPath);
                    }
                }
                currentDataOut = null;
            }
        }

        public List<Path> getExportedFiles() {
            return exportedFiles;
        }
    }

    private int exportCollection(MongoBsonRotator rotator, MongoCollection<Document> collection, String collectionName,
                                 MongoSecureExportConfig config, String saltKey, MongoSecureExportProgress progress) throws Exception {
        rotator.startCollection(collectionName);

        int count = 0;
        for (Document originalDoc : collection.find()) {
            count++;
            if (progress != null) {
                progress.totalRecords.incrementAndGet();
            }

            Document maskedDoc = maskDocument(originalDoc, collectionName, config, saltKey);
            byte[] bsonBytes = encodeDocumentToBson(maskedDoc);
            rotator.writeDoc(bsonBytes);
        }

        rotator.endCollection();
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
        if (rules.getNullifyColumns() != null) {
            for (String col : rules.getNullifyColumns()) {
                if (col.startsWith(collectionName + ".")) {
                    String fieldPath = col.substring(collectionName.length() + 1);
                    maskFieldInDocument(copy, fieldPath, "NULL", saltKey);
                }
            }
        }
        if (rules.getDefaultValues() != null) {
            for (Map.Entry<String, String> entry : rules.getDefaultValues().entrySet()) {
                String col = entry.getKey();
                if (col.startsWith(collectionName + ".")) {
                    String fieldPath = col.substring(collectionName.length() + 1);
                    maskFieldInDocument(copy, fieldPath, "DEFAULT:" + entry.getValue(), saltKey);
                }
            }
        }
        if (rules.getCustomFormats() != null) {
            for (Map.Entry<String, String> entry : rules.getCustomFormats().entrySet()) {
                String col = entry.getKey();
                if (col.startsWith(collectionName + ".")) {
                    String fieldPath = col.substring(collectionName.length() + 1);
                    maskFieldInDocument(copy, fieldPath, "CMF:" + entry.getValue(), saltKey);
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
            if ("NULL".equalsIgnoreCase(ruleType)) {
                doc.put(currentKey, null);
            } else if (ruleType != null && ruleType.startsWith("DEFAULT:")) {
                String defVal = ruleType.substring("DEFAULT:".length());
                doc.put(currentKey, defVal);
            } else if (ruleType != null && ruleType.startsWith("CMF:")) {
                String fmtRule = ruleType.substring("CMF:".length());
                if (dataMaskingService != null && fmtRule != null && !fmtRule.trim().isEmpty()) {
                    doc.put(currentKey, dataMaskingService.applyCustomFormat(currentVal, fmtRule));
                }
            } else if (currentVal != null) {
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
        com.enterprise.seedm.util.BackupFileUtils.encryptFileWithSalt(sourceFile, targetEncryptedFile, saltKey);
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
            Long cosId = config.getDest().getCosId() != null ? config.getDest().getCosId() : config.getDest().getId();
            if (cosId != null && cosConnectionService != null) {
                CosConnection cos = cosConnectionService.getConnection(cosId);
                if (cos != null && cos.getStorageLocation() != null && !cos.getStorageLocation().isBlank()) {
                    return Paths.get(cos.getStorageLocation().trim());
                }
            }
            if (config.getDest().getDestDir() != null && !config.getDest().getDestDir().trim().isEmpty()) {
                return Paths.get(config.getDest().getDestDir().trim());
            }
            if (config.getDest().getPath() != null && !config.getDest().getPath().trim().isEmpty()) {
                return Paths.get(config.getDest().getPath().trim());
            }
        }
        if (config.getStorage() != null) {
            Long sId = config.getStorage().getCosId() != null ? config.getStorage().getCosId() : config.getStorage().getId();
            if (sId != null && cosConnectionService != null) {
                CosConnection cos = cosConnectionService.getConnection(sId);
                if (cos != null && cos.getStorageLocation() != null && !cos.getStorageLocation().isBlank()) {
                    return Paths.get(cos.getStorageLocation().trim());
                }
            }
            if (config.getStorage().getPath() != null && !config.getStorage().getPath().trim().isEmpty()) {
                return Paths.get(config.getStorage().getPath().trim());
            }
        }
        return Paths.get(DEFAULT_EXPORT_DIR);
    }

    private void saveJobRecord(String executionId, MongoSecureExportConfig config, String status, String errorMessage) {
        if (jobRepository == null) return;
        try {
            MongoSecureExportProgress progress = progressMap.get(executionId);
            MongoSecureExportJob job = jobRepository.findByExecutionId(executionId);
            if (job == null) {
                job = new MongoSecureExportJob();
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
                if (job.getFileEncryptionKey() == null && progress.fileEncryptionKey != null) {
                    job.setFileEncryptionKey(progress.fileEncryptionKey);
                }
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
            log.error("Failed to save MongoSecureExportJob record", e);
        }
    }

    public Map<String, Object> getProgress(String executionId) {
        MongoSecureExportProgress progress = progressMap.get(executionId);
        if (progress != null) {
            return progress.toMap();
        }

        if (jobRepository == null) {
            return Map.of("status", "NOT_FOUND");
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
        map.put("fileEncryptionKey", job.getFileEncryptionKey());
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
                log.warn("Failed to parse progress details for mongo export: {}", executionId, e);
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
        for (Map.Entry<String, MongoSecureExportProgress> entry : progressMap.entrySet()) {
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
            for (MongoSecureExportJob job : jobRepository.findAllByOrderByCreatedAtDesc()) {
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
